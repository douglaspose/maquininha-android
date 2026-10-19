package br.com.gestaorestaurante.maquininha

import br.com.gestaorestaurante.maquininha.api.Api
import br.com.gestaorestaurante.maquininha.api.TrabalhoDeImpressao
import br.com.gestaorestaurante.maquininha.impressao.Cupom
import br.com.gestaorestaurante.maquininha.impressao.LoteDeImpressao
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * O lote da fila, trabalho a trabalho.
 *
 * O laço tratava o lote inteiro num `try` só: um papel que estourasse levava
 * junto todos os que vinham atrás, voltava na próxima volta e estourava de
 * novo — a cozinha parava de receber **tudo** por causa de uma comanda, a cada
 * três segundos, para sempre. O que estes testes prendem é que cada trabalho é
 * uma história sua, e que a confirmação não se perde em silêncio.
 */
class LoteDeImpressaoTest {

    private fun trabalho(id: String) =
        TrabalhoDeImpressao(id = id, titulo = "Comanda", conteudo = "1x Costela")

    private val papel = Cupom(titulo = "Comanda", linhas = listOf(Cupom.Linha("1x Costela")))

    /** Registra o que o servidor ficou sabendo de cada trabalho. */
    private class Confirmacoes {
        val feitas = mutableListOf<Triple<String, Boolean, String?>>()
        fun de(id: String) = feitas.firstOrNull { it.first == id }
    }

    @Test
    fun `lote normal imprime e confirma todos`() = runTest {
        val confirmadas = Confirmacoes()
        val impressos = mutableListOf<String>()
        val lote = LoteDeImpressao(
            montar = { papel },
            imprimir = { impressos += "saiu"; null },
            confirmar = { id, ok, erro -> confirmadas.feitas += Triple(id, ok, erro) },
        )

        val feito = lote.rodar(listOf(trabalho("t1"), trabalho("t2")))

        assertEquals(2, feito.impressos)
        assertEquals(0, feito.comErro)
        assertEquals(false, feito.interrompido)
        assertEquals(2, impressos.size)
        assertEquals(true, confirmadas.de("t1")?.second)
    }

    /**
     * O caso que parava a cozinha: um trabalho que não vira papel ficava sem
     * `ok=false`, voltava na cabeça da fila e levava junto todo mundo que
     * estava atrás dele.
     */
    @Test
    fun `trabalho que nao vira papel sai da fila como erro, e o resto imprime`() = runTest {
        val confirmadas = Confirmacoes()
        val lote = LoteDeImpressao(
            montar = { t -> if (t.id == "ruim") throw IllegalStateException("conteúdo torto") else papel },
            imprimir = { null },
            confirmar = { id, ok, erro -> confirmadas.feitas += Triple(id, ok, erro) },
        )

        val feito = lote.rodar(listOf(trabalho("ruim"), trabalho("bom")))

        assertEquals(1, feito.impressos)
        assertEquals(1, feito.comErro)
        // O ruim sai da fila com o motivo, em vez de ficar travando.
        assertEquals(false, confirmadas.de("ruim")?.second)
        assertTrue(confirmadas.de("ruim")?.third!!.contains("conteúdo torto"))
        // E o de trás imprimiu, que é o ponto.
        assertEquals(true, confirmadas.de("bom")?.second)
    }

    @Test
    fun `impressora que estoura nao derruba o lote`() = runTest {
        val confirmadas = Confirmacoes()
        val lote = LoteDeImpressao(
            montar = { papel },
            imprimir = { if (confirmadas.feitas.isEmpty()) throw RuntimeException("SDK caiu") else null },
            confirmar = { id, ok, erro -> confirmadas.feitas += Triple(id, ok, erro) },
        )

        val feito = lote.rodar(listOf(trabalho("t1"), trabalho("t2")))

        assertEquals(1, feito.impressos)
        assertEquals(1, feito.comErro)
        assertEquals("SDK caiu", confirmadas.de("t1")?.third)
    }

    /** Sem papel é o erro de todo dia: vira `ok=false` com o motivo. */
    @Test
    fun `falha da impressora vira erro com o motivo`() = runTest {
        val confirmadas = Confirmacoes()
        val lote = LoteDeImpressao(
            montar = { papel },
            imprimir = { "Sem papel" },
            confirmar = { id, ok, erro -> confirmadas.feitas += Triple(id, ok, erro) },
        )

        val feito = lote.rodar(listOf(trabalho("t1")))

        assertEquals(0, feito.impressos)
        assertEquals(1, feito.comErro)
        assertEquals(Triple("t1", false, "Sem papel"), confirmadas.de("t1"))
    }

    /**
     * A confirmação é o que tira o trabalho da fila do servidor. Um soluço de
     * rede nela custa papel repetido na cozinha, então ela insiste.
     */
    @Test
    fun `a confirmacao insiste antes de desistir`() = runTest {
        var tentativas = 0
        val lote = LoteDeImpressao(
            montar = { papel },
            imprimir = { null },
            confirmar = { _, _, _ ->
                tentativas++
                if (tentativas < 3) throw IOException("soluço")
            },
        )

        val feito = lote.rodar(listOf(trabalho("t1")))

        assertEquals(3, tentativas)
        assertEquals(1, feito.impressos)
        assertEquals(false, feito.interrompido)
    }

    /**
     * Sem conseguir confirmar, o servidor vai reentregar o que já saiu.
     * Continuar imprimindo o resto só multiplicaria o papel repetido quando
     * ele reentregar o lote inteiro.
     */
    @Test
    fun `rede fora na confirmacao para o lote em vez de imprimir mais`() = runTest {
        var impressos = 0
        val lote = LoteDeImpressao(
            montar = { papel },
            imprimir = { impressos++; null },
            confirmar = { _, _, _ -> throw IOException("sem rede") },
        )

        val feito = lote.rodar(listOf(trabalho("t1"), trabalho("t2"), trabalho("t3")))

        assertTrue(feito.interrompido)
        // Só o primeiro saiu no papel: os outros nem foram tentados.
        assertEquals(1, impressos)
        assertEquals(0, feito.impressos)
    }

    /**
     * A baixa recusada, que é o que o servidor passou a responder.
     *
     * A fila entrega cada papel a um aparelho só, e a baixa é de quem levou.
     * Se a reserva vencer no meio de uma impressão demorada, outro leva o
     * trabalho e imprime também; a confirmação deste chega depois da dele e
     * volta 409.
     *
     * Insistir não muda a resposta, e parar o lote por causa dela deixaria a
     * cozinha sem as comandas de trás — que é o erro que este arquivo existe
     * para prender, na outra forma dele.
     */
    @Test
    fun `baixa recusada nao para o lote, e nao conta como papel que nao saiu`() = runTest {
        var tentativas = 0
        var impressos = 0
        val lote = LoteDeImpressao(
            montar = { papel },
            imprimir = { impressos++; null },
            confirmar = { id, _, _ ->
                tentativas++
                if (id == "t1") throw Api.RegraDaCasa("Este papel foi entregue a outro aparelho.")
            },
        )

        val feito = lote.rodar(listOf(trabalho("t1"), trabalho("t2")))

        assertEquals(false, feito.interrompido)
        // Os dois saíram no papel; só a baixa do primeiro foi recusada.
        assertEquals(2, impressos)
        assertEquals(1, feito.impressos)
        assertEquals(1, feito.recusadas)
        // E não é falha de impressora: ninguém precisa ir olhar a bobina.
        assertEquals(0, feito.comErro)
        // Sem insistir: duas confirmações, uma por trabalho.
        assertEquals(2, tentativas)
    }

    /** 404 é trabalho que não existe mais: mesma conversa, mesmo desfecho. */
    @Test
    fun `trabalho que o servidor nao conhece mais tambem nao para o lote`() = runTest {
        val lote = LoteDeImpressao(
            montar = { papel },
            imprimir = { null },
            confirmar = { _, _, _ -> throw Api.RecusadoPeloServidor("Trabalho não encontrado.", 404) },
        )

        val feito = lote.rodar(listOf(trabalho("t1"), trabalho("t2")))

        assertEquals(false, feito.interrompido)
        assertEquals(2, feito.recusadas)
        assertEquals(0, feito.impressos)
    }
}
