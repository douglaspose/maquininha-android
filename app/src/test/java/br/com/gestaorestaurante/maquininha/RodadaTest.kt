package br.com.gestaorestaurante.maquininha

import br.com.gestaorestaurante.maquininha.api.GrupoDeOpcionais
import br.com.gestaorestaurante.maquininha.api.ItemDoCardapio
import br.com.gestaorestaurante.maquininha.api.OpcionalDoCardapio
import br.com.gestaorestaurante.maquininha.ui.LinhaDoCarrinho
import br.com.gestaorestaurante.maquininha.ui.anotarNaRodada
import br.com.gestaorestaurante.maquininha.ui.apagarCentavo
import br.com.gestaorestaurante.maquininha.ui.digitarCentavo
import br.com.gestaorestaurante.maquininha.ui.porNaRodada
import br.com.gestaorestaurante.maquininha.ui.tirarDaRodada
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A rodada que o garçom monta na mesa.
 *
 * O que estes testes protegem: o que a cozinha vai ler e o que a conta vai
 * cobrar. Um erro aqui não quebra o aplicativo — ele manda o prato errado ou
 * cobra o preço errado, que é pior, porque ninguém percebe até o cliente
 * reclamar.
 */
class RodadaTest {

    private var contador = 0
    private val idPrevisivel = { "linha-${++contador}" }

    private fun item(
        titulo: String,
        precoEmCentavos: Long,
        esgotado: Boolean = false,
        grupos: List<GrupoDeOpcionais> = emptyList(),
    ) = ItemDoCardapio(
        cardapioItemId = "cardapio-$titulo",
        produtoId = "produto-$titulo",
        titulo = titulo,
        precoEmCentavos = precoEmCentavos,
        esgotado = esgotado,
        grupos = grupos,
    )

    private fun opcional(titulo: String, precoEmCentavos: Long = 0) =
        OpcionalDoCardapio(opcionalId = "op-$titulo", titulo = titulo, precoEmCentavos = precoEmCentavos)

    // --- montar a rodada ---

    @Test
    fun `item igual e sem escolha soma na mesma linha`() {
        val cerveja = item("Cerveja", 1200)
        var rodada = porNaRodada(emptyList(), cerveja, novoId = idPrevisivel)
        rodada = porNaRodada(rodada, cerveja, novoId = idPrevisivel)

        assertEquals(1, rodada.size)
        assertEquals(2, rodada[0].quantidade)
    }

    @Test
    fun `escolhas diferentes do mesmo prato abrem linhas diferentes`() {
        val picanha = item("Picanha", 12990)
        var rodada = porNaRodada(emptyList(), picanha, listOf(opcional("Ao ponto")), idPrevisivel)
        rodada = porNaRodada(rodada, picanha, listOf(opcional("Mal passada")), idPrevisivel)

        assertEquals(2, rodada.size)
        assertEquals(listOf(1, 1), rodada.map { it.quantidade })
    }

    @Test
    fun `escolhas iguais somam, mesmo em ordem trocada`() {
        // O diálogo monta a lista na ordem em que o garçom tocou; duas
        // picanhas "ao ponto com bacon" são a mesma linha, tenha ele tocado
        // primeiro no ponto ou no bacon.
        val picanha = item("Picanha", 12990)
        val ponto = opcional("Ao ponto")
        val bacon = opcional("Bacon", 800)

        var rodada = porNaRodada(emptyList(), picanha, listOf(ponto, bacon), idPrevisivel)
        rodada = porNaRodada(rodada, picanha, listOf(bacon, ponto), idPrevisivel)

        assertEquals(1, rodada.size)
        assertEquals(2, rodada[0].quantidade)
    }

    @Test
    fun `linha com observacao nao recebe mais um`() {
        /*
         * O "+" não sabe se o recado vale para a unidade nova. Somando, dois
         * chopes sairiam com "sem colarinho" valendo para os dois.
         */
        val chopp = item("Chopp", 1100)
        var rodada = porNaRodada(emptyList(), chopp, novoId = idPrevisivel)
        rodada = anotarNaRodada(rodada, rodada[0].id, "sem colarinho")
        rodada = porNaRodada(rodada, chopp, novoId = idPrevisivel)

        assertEquals(2, rodada.size)
        assertEquals("sem colarinho", rodada[0].observacao)
        assertNull(rodada[1].observacao)
    }

    @Test
    fun `item esgotado nao entra`() {
        val fora = item("Torresmo", 4490, esgotado = true)
        assertTrue(porNaRodada(emptyList(), fora, novoId = idPrevisivel).isEmpty())
    }

    // --- tirar ---

    @Test
    fun `tirar desce a quantidade e some ao chegar a zero`() {
        val batata = item("Batata", 3990)
        var rodada = porNaRodada(emptyList(), batata, novoId = idPrevisivel)
        rodada = porNaRodada(rodada, batata, novoId = idPrevisivel)

        rodada = tirarDaRodada(rodada, rodada[0].id)
        assertEquals(1, rodada[0].quantidade)

        rodada = tirarDaRodada(rodada, rodada[0].id)
        assertTrue(rodada.isEmpty())
    }

    @Test
    fun `tirar mexe so na linha pedida`() {
        val picanha = item("Picanha", 12990)
        var rodada = porNaRodada(emptyList(), picanha, listOf(opcional("Ao ponto")), idPrevisivel)
        rodada = porNaRodada(rodada, picanha, listOf(opcional("Mal passada")), idPrevisivel)

        val sobrou = tirarDaRodada(rodada, rodada[0].id)

        assertEquals(1, sobrou.size)
        assertEquals("Mal passada", sobrou[0].escolhas[0].titulo)
    }

    // --- observação ---

    @Test
    fun `observacao em branco some em vez de virar vazia`() {
        // Observação vazia gravada vira linha que não agrupa com nenhuma outra.
        val chopp = item("Chopp", 1100)
        var rodada = porNaRodada(emptyList(), chopp, novoId = idPrevisivel)

        rodada = anotarNaRodada(rodada, rodada[0].id, "   ")
        assertNull(rodada[0].observacao)
    }

    @Test
    fun `observacao e cortada em 200 caracteres`() {
        val chopp = item("Chopp", 1100)
        var rodada = porNaRodada(emptyList(), chopp, novoId = idPrevisivel)

        rodada = anotarNaRodada(rodada, rodada[0].id, "a".repeat(250))
        assertEquals(200, rodada[0].observacao?.length)
    }

    // --- preço ---

    @Test
    fun `o preco da linha soma os adicionais por unidade`() {
        val picanha = item("Picanha", 12990)
        val linha = LinhaDoCarrinho(
            id = "x",
            item = picanha,
            quantidade = 2,
            escolhas = listOf(opcional("Bacon", 800), opcional("Ao ponto", 0)),
        )

        // (129,90 + 8,00) x 2 = 275,80 — a mesma conta que o servidor faz.
        assertEquals(27580, linha.precoDaLinhaEmCentavos)
    }

    @Test
    fun `linha sem escolha custa o preco do item`() {
        val linha = LinhaDoCarrinho(id = "x", item = item("Água", 500), quantidade = 3)
        assertEquals(1500, linha.precoDaLinhaEmCentavos)
    }

    // --- teclado ---

    @Test
    fun `digito entra pela direita, em centavos`() {
        var valor = 0L
        for (digito in "1500") valor = digitarCentavo(valor, digito)
        assertEquals(1500, valor)
    }

    @Test
    fun `digito acima do teto e ignorado`() {
        // Dedo pesado não vira conta de milhões: o visor para no limite.
        val teto = 99_999_999L
        assertEquals(teto, digitarCentavo(teto, '9'))
    }

    @Test
    fun `tecla que nao e numero nao mexe no valor`() {
        assertEquals(1500, digitarCentavo(1500, 'x'))
    }

    @Test
    fun `apagar tira o ultimo digito ate zerar`() {
        assertEquals(150, apagarCentavo(1500))
        assertEquals(0, apagarCentavo(5))
        assertEquals(0, apagarCentavo(0))
    }
}
