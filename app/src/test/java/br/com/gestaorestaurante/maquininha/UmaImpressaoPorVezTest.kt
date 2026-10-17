package br.com.gestaorestaurante.maquininha

import br.com.gestaorestaurante.maquininha.impressao.Cupom
import br.com.gestaorestaurante.maquininha.impressao.Impressora
import br.com.gestaorestaurante.maquininha.impressao.UmaImpressaoPorVez
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * A bobina é uma só.
 *
 * Duas partes do aplicativo imprimem — o serviço da fila despejando as
 * comandas da cozinha e a tela da cobrança tirando a via do cliente — e cada
 * uma tinha a sua instância de impressora. Numa térmica de verdade, dois
 * trabalhos ao mesmo tempo saem misturados no mesmo pedaço de papel: o cliente
 * leva metade do comprovante dele com metade da comanda da cozinha.
 */
class UmaImpressaoPorVezTest {

    private val papel = Cupom(titulo = "Comanda", linhas = listOf(Cupom.Linha("1x Costela")))

    /** Conta quantos estão dentro do `imprimir` ao mesmo tempo. */
    private class TermicaQueDemora : Impressora {
        var dentro = 0
        var houveSobreposicao = false
        var saidas = 0

        override val colunas = 32

        override suspend fun imprimir(cupom: Cupom): String? {
            dentro++
            if (dentro > 1) houveSobreposicao = true
            // O tempo de uma bobina: é durante ele que o outro trabalho chega.
            delay(50)
            dentro--
            saidas++
            return null
        }
    }

    @Test
    fun `dois trabalhos ao mesmo tempo saem um depois do outro`() = runTest {
        val termica = TermicaQueDemora()
        val fila = UmaImpressaoPorVez(termica)

        val comanda = launch { fila.imprimir(papel) }
        val viaDoCliente = launch { fila.imprimir(papel) }
        comanda.join()
        viaDoCliente.join()

        assertFalse(termica.houveSobreposicao)
        assertEquals(2, termica.saidas)
    }

    @Test
    fun `a largura continua sendo a da impressora de verdade`() {
        val fila = UmaImpressaoPorVez(TermicaQueDemora())

        assertEquals(32, fila.colunas)
    }
}
