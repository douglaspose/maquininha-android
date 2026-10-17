package br.com.gestaorestaurante.maquininha

import br.com.gestaorestaurante.maquininha.pagamento.Cobranca
import br.com.gestaorestaurante.maquininha.pagamento.FormaDePagamento
import br.com.gestaorestaurante.maquininha.pagamento.ResultadoDaCobranca
import br.com.gestaorestaurante.maquininha.pagamento.TransacaoAprovada
import br.com.gestaorestaurante.maquininha.ui.pagamentoParaContar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * O que o aparelho conta ao servidor depois do "aprovada".
 *
 * A venda de balcão — o cliente em pé, que não abriu conta — era cobrada no
 * cartão e não ia a lugar nenhum: sem comanda para citar, o aparelho nem
 * tentava mandar e ainda dava o resultado como enviado. A casa fechava o caixa
 * com um extrato da adquirente que nenhuma venda do sistema explicava.
 */
class VendaDeBalcaoTest {

    private val cobranca = Cobranca(
        referencia = "venda-do-balcao",
        valorEmCentavos = 1_500,
        forma = FormaDePagamento.CREDITO,
        parcelas = 1,
    )

    private fun aprovada(valorEmCentavos: Long = 1_500) = ResultadoDaCobranca.Aprovada(
        TransacaoAprovada(
            referencia = cobranca.referencia,
            identificadorNaCredenciadora = "SIM123456",
            valorEmCentavos = valorEmCentavos,
            forma = FormaDePagamento.CREDITO,
            parcelas = 1,
            bandeira = "VISA",
            autorizacao = "371111",
            finalDoCartao = "1104",
            viaDoCliente = null,
            viaDaLoja = null,
        )
    )

    /** O buraco: sem conta, nada era mandado. */
    @Test
    fun `a venda sem conta vai para o servidor do mesmo jeito`() {
        val pagamento = pagamentoParaContar(aprovada(), cobranca, comandaId = null)

        assertNotNull(pagamento)
        assertNull(pagamento!!.comandaId)
        assertEquals("venda-do-balcao", pagamento.referencia)
        assertEquals(1_500L, pagamento.valorEmCentavos)
        assertEquals("CREDITO", pagamento.forma)
        assertEquals("SIM123456", pagamento.nsu)
        assertEquals("VISA", pagamento.bandeira)
    }

    @Test
    fun `a venda de uma mesa continua indo com a conta dela`() {
        val pagamento = pagamentoParaContar(aprovada(), cobranca, comandaId = "mesa-8")

        assertEquals("mesa-8", pagamento?.comandaId)
    }

    /**
     * Recusa não moveu dinheiro: não há o que registrar, e mandar assim mesmo
     * poria no faturamento uma venda que não houve.
     */
    @Test
    fun `recusada nao vira pagamento`() {
        val recusada = ResultadoDaCobranca.Recusada("Saldo insuficiente", "51")

        assertNull(pagamentoParaContar(recusada, cobranca, comandaId = null))
        assertNull(pagamentoParaContar(recusada, cobranca, comandaId = "mesa-8"))
    }

    @Test
    fun `cancelada e erro tambem nao viram pagamento`() {
        assertNull(pagamentoParaContar(ResultadoDaCobranca.Cancelada, cobranca, null))
        assertNull(pagamentoParaContar(ResultadoDaCobranca.Erro("sem papel"), cobranca, null))
    }

    /**
     * O valor que vai é o que a credenciadora capturou, e não o que foi
     * digitado: ajustado no visor, os dois diferem — e o que a casa recebeu é
     * o da credenciadora.
     */
    @Test
    fun `vai o valor da credenciadora, nao o digitado`() {
        val pagamento = pagamentoParaContar(aprovada(valorEmCentavos = 1_200), cobranca, null)

        assertEquals(1_200L, pagamento?.valorEmCentavos)
    }
}
