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
)

/** Uma linha do carrinho, do jeito que o servidor recebe. */
@Serializable
data class ItemParaLancar(
    val cardapioItemId: String,
    val quantidade: Int = 1,
    val observacao: String? = null,
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
