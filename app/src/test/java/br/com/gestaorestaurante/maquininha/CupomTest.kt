package br.com.gestaorestaurante.maquininha

import br.com.gestaorestaurante.maquininha.impressao.lerConteudoDaFila
import br.com.gestaorestaurante.maquininha.impressao.paraLargura
import br.com.gestaorestaurante.maquininha.ui.emReais
import br.com.gestaorestaurante.maquininha.ui.esperaDesde
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
    fun `regua de separacao e cortada, nao quebrada em duas`() {
        // O que apareceu no primeiro cupom de verdade: 48 hífens viravam uma
        // linha de 32 e outra de 16, duas réguas tortas no papel.
        val cupom = lerConteudoDaFila("Cupom", "-".repeat(48), null).paraLargura(32)

        assertEquals(1, cupom.linhas.size)
        assertEquals("-".repeat(32), cupom.linhas[0].texto)
    }

    @Test
    fun `texto repetitivo de verdade continua quebrando`() {
        // A régua é reconhecida por ser pontuação repetida; uma palavra
        // repetida é texto e continua sendo quebrada em linhas.
        val cupom = lerConteudoDaFila("Cupom", "aaaa ".repeat(12).trim(), null).paraLargura(32)

        assertTrue(cupom.linhas.size > 1)
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

    /**
     * O que já foi pago entra na conta como valor negativo. Com o sinal no
     * meio do número, a tela da mesa mostrava "R$ -22,-94".
     */
    @Test
    fun `valor negativo leva o sinal na frente`() {
        assertEquals("-R$ 22,94", "-" + emReais(2294))
        assertEquals("R$ -22,94", emReais(-2294))
        assertEquals("R$ -1.234,56", emReais(-123456))
        assertEquals("R$ -0,05", emReais(-5))
    }

    /**
     * A espera da mesa, que é o que o garçom lê para decidir aonde vai antes.
     * O relógio é fixo nos testes: "há 3 min" medido contra o agora de verdade
     * passaria hoje e falharia na virada do minuto.
     */
    @Test
    fun `a espera sai em palavras curtas`() {
        val agora = instante("2026-10-16T20:00:00.000Z")
        assertEquals("agora", esperaDesde("2026-10-16T19:59:30.000Z", agora))
        assertEquals("há 3 min", esperaDesde("2026-10-16T19:57:00.000Z", agora))
        assertEquals("há 59 min", esperaDesde("2026-10-16T19:01:00.000Z", agora))
        assertEquals("há 1h12", esperaDesde("2026-10-16T18:48:00.000Z", agora))
        assertEquals("há 2h05", esperaDesde("2026-10-16T17:55:00.000Z", agora))
    }

    /** Relógio do aparelho atrasado em relação ao do servidor daria "há -2 min". */
    @Test
    fun `instante no futuro vira agora`() {
        val agora = instante("2026-10-16T20:00:00.000Z")
        assertEquals("agora", esperaDesde("2026-10-16T20:02:00.000Z", agora))
    }

    /**
     * Uma mesa sem o "há 3 min" ainda é uma mesa; uma lista do salão que
     * estourou ao desenhar, não.
     */
    @Test
    fun `data ilegivel nao derruba a lista do salao`() {
        assertEquals(null, esperaDesde("ontem à noite"))
        assertEquals(null, esperaDesde(""))
    }

    private fun instante(iso: String): Long {
        val formato = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
        formato.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return formato.parse(iso)!!.time
    }
}
