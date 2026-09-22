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

// --- Cobrança (contrato proposto, ainda não implementado no servidor) ---

@Serializable
data class RespostaDeCobrancas(
    val cobrancas: List<CobrancaPendente> = emptyList(),
)

@Serializable
data class CobrancaPendente(
    val id: String,
    @SerialName("valorEmCentavos") val valorEmCentavos: Long,
    /** CREDITO, CREDITO_PARCELADO, DEBITO, PIX, VOUCHER. */
    val forma: String,
    val parcelas: Int = 1,
    /** O que aparece na tela da maquininha: "Mesa 7 · João". */
    val descricao: String = "",
)

@Serializable
data class ResultadoDaCobrancaEnviado(
    val id: String,
    /** APROVADA, RECUSADA, CANCELADA ou ERRO. */
    val resultado: String,
    val identificadorNaCredenciadora: String? = null,
    val credenciadora: String? = null,
    val bandeira: String? = null,
    val autorizacao: String? = null,
    val finalDoCartao: String? = null,
    val parcelas: Int? = null,
    val motivo: String? = null,
)
