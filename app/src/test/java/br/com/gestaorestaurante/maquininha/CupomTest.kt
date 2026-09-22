package br.com.gestaorestaurante.maquininha

import br.com.gestaorestaurante.maquininha.impressao.lerConteudoDaFila
import br.com.gestaorestaurante.maquininha.impressao.paraLargura
import br.com.gestaorestaurante.maquininha.ui.emReais
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O que estes testes protegem: o cupom que sai do papel.
 *
 * É a única parte do aplicativo que dá para conferir sem aparelho, sem SDK e
 * sem servidor — e é também onde um erro só aparece depois, quando alguém no
 * caixa nota que o valor sumiu do fim da linha.
 */
class CupomTest {

    @Test
    fun `letra dupla chega marcada e sem os marcadores no texto`() {
        val conteudo = "Mesa 7\nTOTAL 120,00\nobrigado"
        val cupom = lerConteudoDaFila("Conferência", conteudo, null)

        assertEquals(3, cupom.linhas.size)
        assertEquals(listOf(false, true, false), cupom.linhas.map { it.letraDupla })
        assertEquals("TOTAL 120,00", cupom.linhas[1].texto)
    }

    @Test
    fun `valor no fim da linha sobrevive ao estreitamento`() {
        // 48 colunas: descrição à esquerda, valor à direita.
        val linha = "Picanha na chapa" + " ".repeat(20) + "R\$ 129,90"
        val cupom = lerConteudoDaFila("Cupom", linha, null).paraLargura(32)

        assertTrue(
            "o valor não pode se perder no corte",
            cupom.linhas.any { it.texto.contains("R\$ 129,90") },
        )
        assertTrue(cupom.linhas.all { it.texto.length <= 32 })
    }

    @Test
    fun `descricao longa desce o valor para a linha seguinte alinhado a direita`() {
        val linha = "Combo familia inteira com fritas e refrigerante" + "  " + "R\$ 249,90"
        val cupom = lerConteudoDaFila("Cupom", linha, null).paraLargura(32)

        assertEquals(2, cupom.linhas.size)
        assertEquals(32, cupom.linhas[1].texto.length)
        assertTrue(cupom.linhas[1].texto.endsWith("R\$ 249,90"))
    }

    @Test
    fun `em letra dupla cabe metade das colunas`() {
        val conteudo = "TOTAL DA MESA 7 --- R\$ 1.234,56"
        val cupom = lerConteudoDaFila("Conferência", conteudo, null).paraLargura(32)

        assertTrue(
            "letra dupla ocupa dois caracteres por letra",
            cupom.linhas.all { it.texto.length <= 16 },
        )
    }

    @Test
    fun `linha que ja cabe nao e mexida`() {
        val cupom = lerConteudoDaFila("Cupom", "Mesa 7", null).paraLargura(32)
        assertEquals(listOf("Mesa 7"), cupom.linhas.map { it.texto })
    }

    @Test
    fun `centavos viram reais com ponto de milhar`() {
        assertEquals("R$ 0,05", emReais(5))
        assertEquals("R$ 15,50", emReais(1550))
        assertEquals("R$ 1.234,56", emReais(123456))
        assertEquals("R$ 0,00", emReais(0))
    }
}
