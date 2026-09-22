package br.com.gestaorestaurante.maquininha

import android.content.Context
import br.com.gestaorestaurante.maquininha.impressao.Cupom
import br.com.gestaorestaurante.maquininha.impressao.Impressora
import br.com.gestaorestaurante.maquininha.pagamento.AndamentoDaCobranca
import br.com.gestaorestaurante.maquininha.pagamento.Cobranca
import br.com.gestaorestaurante.maquininha.pagamento.ProvedorDePagamento
import br.com.gestaorestaurante.maquininha.pagamento.ResultadoDaCobranca
import br.com.gestaorestaurante.maquininha.pagamento.TransacaoAprovada

/**
 * A Stone.
 *
 * Este arquivo está com a forma pronta e o miolo vazio — de propósito. A SDK
 * da Stone não vem do Maven Central: mora num repositório privado do
 * PackageCloud cujo endereço já embute um token que eles mandam por e-mail no
 * onboarding. Sem esse token não há como baixá-la, e um arquivo que importa
 * classes inexistentes não compila nem para quem só quer mexer nas telas.
 *
 * Então o flavor `stone` compila hoje e falha em tempo de execução dizendo o
 * que falta. Quando o token chegar:
 *
 *   1. `local.properties`: preencha `stone.packagecloud.token` e `stone.code`.
 *   2. `app/build.gradle.kts`: descomente a linha do `stoneImplementation`,
 *      com o número de versão que veio no e-mail deles.
 *   3. Troque os corpos daqui pelas chamadas da SDK. Os pontos estão marcados.
 *
 * O resto do aplicativo não muda uma linha — é para isso que
 * [ProvedorDePagamento] existe.
 *
 * Referência: https://sdkandroid.stone.com.br/reference/preparando-aplicacao
 */
object PlataformaAtual : Plataforma {

    override val credenciadora = "Stone"

    override fun pagamento(contexto: Context): ProvedorDePagamento = ProvedorStone(contexto)

    override fun impressora(contexto: Context): Impressora = ImpressoraStone(contexto)
}

class ProvedorStone(private val contexto: Context) : ProvedorDePagamento {

    override val nome = "Stone"

    /**
     * Na SDK isto é `StoneStart.init(contexto)` seguido de
     * `ActiveApplicationProvider`, que ativa o aparelho com o Stone Code e só
     * precisa rodar uma vez por instalação.
     *
     * Vale lembrar de dois detalhes que custam tempo em campo: a ativação
     * exige internet e o Stone Code é por estabelecimento, não por aparelho.
     */
    override suspend fun preparar(): Boolean {
        val codigo = BuildConfig.STONE_CODE
        if (codigo.isBlank()) return false

        // TODO(stone): StoneStart.init(contexto) + ActiveApplicationProvider.
        return false
    }

    /**
     * Na SDK isto é um `TransactionProvider` com um `TransactionObject`
     * montado a partir da [Cobranca]: valor em centavos, `TypeOfTransaction`,
     * número de parcelas e `InstalmentTransactionEnum`.
     *
     * Dois cuidados que valem mais do que parecem:
     *
     *  - O callback de status da SDK é quem alimenta [aoAndar]: é o texto que
     *    diz ao cliente para aproximar, inserir ou digitar a senha.
     *  - `initiatorTransactionKey` recebe a [Cobranca.referencia]. É o campo
     *    que amarra a transação da Stone à conta do salão, e é o que salva a
     *    conciliação quando a resposta se perde no caminho.
     */
    override suspend fun cobrar(
        cobranca: Cobranca,
        aoAndar: (AndamentoDaCobranca) -> Unit,
    ): ResultadoDaCobranca {
        // TODO(stone): TransactionProvider.
        return ResultadoDaCobranca.Erro(
            "SDK da Stone ainda não integrada neste build."
        )
    }

    /** Na SDK, `CancellationProvider`. Só cancela transação do próprio dia. */
    override suspend fun cancelar(transacao: TransacaoAprovada): ResultadoDaCobranca {
        // TODO(stone): CancellationProvider.
        return ResultadoDaCobranca.Erro(
            "SDK da Stone ainda não integrada neste build."
        )
    }
}

/**
 * A impressora embutida da maquininha.
 *
 * Na SDK é o `PrinterProvider`, e ele não fala ESC/POS: recebe uma lista de
 * itens (texto com alinhamento e tamanho, ou bitmap). Por isso o cupom chega
 * aqui já quebrado em linhas por `lerConteudoDaFila` e já estreitado por
 * `paraLargura` — o que falta é só traduzir cada linha num item da SDK, e a
 * logo (que vem como raster de 1 bit) num `Bitmap`.
 */
class ImpressoraStone(private val contexto: Context) : Impressora {

    // 32 colunas na fonte padrão da térmica de 58mm das maquininhas Stone.
    // Confirme no aparelho de homologação antes de confiar neste número.
    override val colunas = 32

    override suspend fun imprimir(cupom: Cupom): String? {
        // TODO(stone): PrinterProvider.
        return "SDK da Stone ainda não integrada neste build."
    }
}
