package br.com.gestaorestaurante.maquininha.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Os corpos JSON que o servidor manda e espera.
 *
 * A parte de impressão é o contrato que já existe e está documentado em
 * `docs/impressao.md` do repositório do sistema. A parte de cobrança é
 * proposta — veja `docs/api-maquininha.md` aqui deste repositório.
 */

@Serializable
data class RespostaDaFila(
    val unidade: String = "",
    val marca: MarcaImpressa? = null,
    /**
     * A largura em que o servidor montou estes papéis.
     *
     * Zero quando o servidor é antigo e não conhece o campo — e é assim que o
     * aplicativo sabe que precisa estreitar o texto por conta própria.
     */
    val colunas: Int = 0,
    val trabalhos: List<TrabalhoDeImpressao> = emptyList(),
)

@Serializable
data class MarcaImpressa(
    /** ESC/POS pronto, em base64: `ESC a 1`, `GS v 0` com o bitmap, `ESC a 0`. */
    val escpos: String,
    val largura: Int = 0,
    val altura: Int = 0,
)

@Serializable
data class TrabalhoDeImpressao(
    val id: String,
    val tipo: String = "",
    val titulo: String = "",
    val conteudo: String = "",
    val comMarca: Boolean = false,
    val impressora: ImpressoraDoTrabalho? = null,
)

@Serializable
data class ImpressoraDoTrabalho(
    val nome: String = "",
    val conexao: String = "",
    val endereco: String = "",
)

@Serializable
data class ConfirmacaoDeImpressao(
    val id: String,
    val ok: Boolean,
    val erro: String? = null,
)


// --- Salão: mesas abertas, conta e pagamento (docs/maquininha.md do sistema) ---

@Serializable
data class PinDoOperador(val pin: String)

@Serializable
data class RespostaDoOperador(
    val token: String = "",
    val nome: String = "",
    val cargo: String = "",
    val unidade: String = "",
)

@Serializable
data class RespostaDeMesas(
    val unidade: String = "",
    val operador: String = "",
    /** Sem caixa aberto nenhum pagamento entra; a tela avisa antes de cobrar. */
    val caixaAberto: Boolean = true,
    val mesas: List<MesaAberta> = emptyList(),
    /** O operador pode abrir mesa; sem isso o aparelho nem mostra o botão. */
    val podeAbrir: Boolean = false,
    /**
     * O operador recebe pagamento. Verdadeiro por omissão: servidor antigo não
     * manda o campo, e esse servidor também não deixa entrar quem não recebe —
     * o aparelho do caixa continua igual.
     */
    val podeReceber: Boolean = true,
    /** As mesas sem conta aberta, para o garçom que recebe quem acabou de sentar. */
    val livres: List<MesaLivre> = emptyList(),
    /** A casa exige o nome do cliente para abrir — o aparelho pergunta antes. */
    val exigeNomeCliente: Boolean = false,
)

@Serializable
data class MesaLivre(
    val mesaId: String,
    /** Texto, não número: a casa batiza mesa de "12A". */
    val numero: String = "",
    val capacidade: Int = 0,
    val area: String? = null,
)

/** O que a maquininha manda para abrir a mesa. */
@Serializable
data class AberturaDeMesa(
    val mesaId: String,
    val pessoas: Int,
    val nomeCliente: String? = null,
)

@Serializable
data class RespostaDaAbertura(
    val ok: Boolean = false,
    val comandaId: String = "",
    /**
     * A mesa já tinha conta: ou outro garçom abriu, ou é este aparelho
     * insistindo depois de a rede engasgar. Nos dois casos a conta devolvida é
     * a que vale, e quem chama não precisa distinguir para seguir.
     */
    val jaEstavaAberta: Boolean = false,
)

@Serializable
data class MesaAberta(
    val comandaId: String,
    val numero: Int = 0,
    /** "Mesa 7", ou nulo quando a comanda não tem mesa (balcão). */
    val mesa: String? = null,
    val nomeCliente: String? = null,
    val pessoas: Int = 1,
    /** A conta já foi pedida: é onde o caixa costuma estar. */
    val fechando: Boolean = false,
    val totalEmCentavos: Long = 0,
    val recebidoEmCentavos: Long = 0,
    val faltaEmCentavos: Long = 0,
)

@Serializable
data class ContaDaMesa(
    val comandaId: String = "",
    val numero: Int = 0,
    val mesa: String? = null,
    val nomeCliente: String? = null,
    val pessoas: Int = 1,
    val subtotalEmCentavos: Long = 0,
    val descontoEmCentavos: Long = 0,
    val taxaServicoEmCentavos: Long = 0,
    val totalEmCentavos: Long = 0,
    val recebidoEmCentavos: Long = 0,
    val faltaEmCentavos: Long = 0,
    val porPessoaEmCentavos: Long? = null,
    val itens: List<ItemDaConta> = emptyList(),
    val pagamentos: List<PagamentoDaConta> = emptyList(),
)

@Serializable
data class ItemDaConta(
    val titulo: String = "",
    val quantidade: Double = 1.0,
    val totalEmCentavos: Long = 0,
)

@Serializable
data class PagamentoDaConta(
    val forma: String = "",
    val valorEmCentavos: Long = 0,
    val em: String = "",
)

/**
 * O que a maquininha conta ao servidor depois de a credenciadora aprovar.
 *
 * `referencia` é o que impede o pagamento em dobro: reenviada, o servidor
 * reconhece o mesmo pagamento em vez de criar outro.
 */
@Serializable
data class PagamentoParaEnviar(
    val comandaId: String,
    val referencia: String,
    /** CREDITO, CREDITO_PARCELADO, DEBITO, PIX ou VOUCHER. */
    val forma: String,
    val valorEmCentavos: Long,
    val nsu: String? = null,
    val bandeira: String? = null,
    /** Quitando a conta, o servidor a finaliza na mesma chamada. */
    val finalizar: Boolean = true,
)

@Serializable
data class RespostaDoPagamento(
    val ok: Boolean = false,
    val jaRegistrado: Boolean = false,
    val forma: String = "",
    val faltaEmCentavos: Long = 0,
    val quitada: Boolean = false,
    val finalizada: Boolean = false,
)

/** O corpo de erro do servidor: uma frase pronta para a tela. */
@Serializable
data class ErroDoServidor(val erro: String = "")

// --- Cardápio e lançamento de itens ---

@Serializable
data class RespostaDoCardapio(
    val unidade: String = "",
    val categorias: List<CategoriaDoCardapio> = emptyList(),
)

@Serializable
data class CategoriaDoCardapio(
    val id: String = "",
    val nome: String = "",
    val itens: List<ItemDoCardapio> = emptyList(),
)

@Serializable
data class ItemDoCardapio(
    val cardapioItemId: String = "",
    val produtoId: String = "",
    val titulo: String = "",
    val descricao: String? = null,
    val precoEmCentavos: Long = 0,
    /**
     * Esgotado aparece na lista, apagado e sem toque. Sumir com ele faria o
     * garçom procurar na tela um prato que ele sabe que existe.
     */
    val esgotado: Boolean = false,
    val exigePontoCarne: Boolean = false,
    /** O que o cliente escolhe: ponto, adicionais, acompanhamento. */
    val grupos: List<GrupoDeOpcionais> = emptyList(),
)

@Serializable
data class GrupoDeOpcionais(
    val id: String = "",
    val titulo: String = "",
    /**
     * Quantas escolhas o grupo aceita. `min` 1 com `obrigatorio` é o "escolha
     * o ponto da carne"; `max` 3 é o "até três adicionais".
     */
    val minEscolhas: Int = 0,
    val maxEscolhas: Int = 1,
    val obrigatorio: Boolean = false,
    val itens: List<OpcionalDoCardapio> = emptyList(),
) {
    /** Um grupo de uma escolha só é rádio; de várias, caixa de marcar. */
    val escolhaUnica: Boolean get() = maxEscolhas == 1

    val minimoDeVerdade: Int get() = if (obrigatorio) maxOf(1, minEscolhas) else minEscolhas
}

@Serializable
data class OpcionalDoCardapio(
    val opcionalId: String = "",
    val titulo: String = "",
    /** Zero no que não cobra — "sem cebola" não tem preço. */
    val precoEmCentavos: Long = 0,
)

/** Uma escolha feita, do jeito que o servidor recebe. */
@Serializable
data class EscolhaParaLancar(
    val opcionalId: String,
    val quantidade: Int = 1,
)

/** Uma linha do carrinho, do jeito que o servidor recebe. */
@Serializable
data class ItemParaLancar(
    val cardapioItemId: String,
    /**
     * A linha do carrinho que originou este item.
     *
     * Vai igual em toda tentativa: é ela que faz o reenvio de uma rodada
     * presa na rede não lançar o pedido duas vezes.
     */
    val referencia: String? = null,
    val quantidade: Int = 1,
    val observacao: String? = null,
    /** Só os identificadores: título e preço saem do cadastro, no servidor. */
    val opcionais: List<EscolhaParaLancar> = emptyList(),
)

@Serializable
data class RodadaParaLancar(
    val itens: List<ItemParaLancar>,
    /** Ligado: o garçom que lançou na mesa quer o prato andando. */
    val enviar: Boolean = true,
)

@Serializable
data class RespostaDaRodada(
    val ok: Boolean = false,
    val lancados: Int = 0,
    val enviados: Int = 0,
)
