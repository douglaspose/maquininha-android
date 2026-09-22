package br.com.gestaorestaurante.maquininha.pagamento

/**
 * O contrato de pagamento do aplicativo — escrito sem uma palavra de Stone,
 * de Rede ou de qualquer outra.
 *
 * É ele que permite trocar de credenciadora (ou rodar as duas no mesmo
 * cardápio de maquininhas) sem reescrever tela nenhuma: cada credenciadora
 * entra como uma implementação de [ProvedorDePagamento] no seu flavor, e o
 * resto do aplicativo continua conversando só com o que está aqui.
 */

/** Como a venda vai ser paga. Não é a bandeira, é o meio. */
enum class FormaDePagamento(val rotulo: String) {
    CREDITO("Crédito"),
    CREDITO_PARCELADO("Crédito parcelado"),
    DEBITO("Débito"),
    PIX("Pix"),
    VOUCHER("Voucher"),
}

/**
 * Uma cobrança pedida pelo servidor.
 *
 * `valorEmCentavos` é inteiro de propósito: dinheiro em `Double` sempre acaba
 * errando um centavo em algum arredondamento, e é o tipo de erro que só
 * aparece no fechamento do caixa.
 *
 * `referencia` é o identificador da cobrança no servidor. Vai junto para a
 * credenciadora e volta no comprovante — é por ela que se descobre, no dia
 * seguinte, a qual conta pertenceu uma transação aprovada cuja resposta se
 * perdeu no caminho.
 */
data class Cobranca(
    val referencia: String,
    val valorEmCentavos: Long,
    val forma: FormaDePagamento,
    val parcelas: Int = 1,
    /** Quem assume os juros no parcelado: a casa (true) ou o cliente. */
    val parceladoPelaLoja: Boolean = true,
)

/** Uma transação que deu certo, do jeito que o servidor precisa guardá-la. */
data class TransacaoAprovada(
    val referencia: String,
    /** O identificador da transação na credenciadora (para estorno e conciliação). */
    val identificadorNaCredenciadora: String,
    val valorEmCentavos: Long,
    val forma: FormaDePagamento,
    val parcelas: Int,
    val bandeira: String?,
    val autorizacao: String?,
    /** Os quatro últimos dígitos, quando houver cartão. Nunca o número inteiro. */
    val finalDoCartao: String?,
    /** Comprovante já formatado pela credenciadora: via do cliente e via da loja. */
    val viaDoCliente: String?,
    val viaDaLoja: String?,
)

/**
 * O que pode sair de uma cobrança.
 *
 * `Recusada` e `Erro` são coisas diferentes e o caixa precisa saber qual foi:
 * recusada é o emissor dizendo não (tenta outro cartão), erro é o caminho
 * falhando (tenta de novo, e a venda pode ter passado).
 */
sealed interface ResultadoDaCobranca {
    data class Aprovada(val transacao: TransacaoAprovada) : ResultadoDaCobranca
    data class Recusada(val motivo: String, val codigo: String?) : ResultadoDaCobranca
    data object Cancelada : ResultadoDaCobranca
    data class Erro(val motivo: String, val causa: Throwable? = null) : ResultadoDaCobranca
}

/**
 * O que a maquininha está fazendo agora, para a tela ter o que mostrar
 * enquanto a transação corre. Os textos vêm da credenciadora quando ela os
 * manda; estes são o que a tela mostra quando ela não manda nada.
 */
data class AndamentoDaCobranca(val mensagem: String)

/**
 * Uma credenciadora, vista de fora.
 *
 * Implementações moram nos flavors (`src/stone`, `src/simulado`), nunca aqui.
 */
interface ProvedorDePagamento {
    /** Nome curto para a tela e para os registros: "Stone", "Rede", "Simulado". */
    val nome: String

    /**
     * Liga a SDK e confere se este aparelho está ativado na credenciadora.
     * Roda uma vez na abertura do aplicativo; é a chamada que costuma falhar
     * quando o Stone Code está errado ou o aparelho nunca foi ativado.
     */
    suspend fun preparar(): Boolean

    /**
     * Cobra e só volta quando a transação terminou — aprovada, recusada,
     * cancelada pelo cliente ou estourada por erro. Nunca lança: o que deu
     * errado volta como [ResultadoDaCobranca.Erro], porque uma exceção
     * atravessando a tela no meio de uma venda é pior do que qualquer recusa.
     */
    suspend fun cobrar(
        cobranca: Cobranca,
        aoAndar: (AndamentoDaCobranca) -> Unit,
    ): ResultadoDaCobranca

    /**
     * Cancela uma transação do próprio dia. Estorno de dia anterior é outra
     * história (passa pelo portal da credenciadora) e não entra aqui.
     */
    suspend fun cancelar(transacao: TransacaoAprovada): ResultadoDaCobranca
}
