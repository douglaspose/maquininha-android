package br.com.gestaorestaurante.maquininha

import br.com.gestaorestaurante.maquininha.ui.esperaDesde
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A espera de cada mesa, calculada de várias threads ao mesmo tempo.
 *
 * O formatador saiu de dentro da função — construir um por mesa a cada
 * redesenho do salão era caro — e passou a viver por thread. Por thread, e não
 * um só compartilhado: `SimpleDateFormat` guarda estado enquanto analisa, e
 * dois usos simultâneos devolvem data trocada ou estouram. Na tela isso vira
 * uma espera errada — "há 3 min" numa mesa que levantou a mão faz vinte — ou a
 * lista inteira sem os minutos.
 *
 * Este teste existe por causa dessa troca: ele é o que separa "mais rápido" de
 * "mais rápido e errado".
 */
class EsperaConcorrenteTest {

    @Test
    fun `a mesma espera sai igual, venha de quantas threads vier`() {
        // 2026-10-17T19:00:00Z, e o agora meia hora depois.
        val iso = "2026-10-17T19:00:00.000Z"
        val agora = 1792_000_000_000L
        val esperado = esperaDesde(iso, agora)

        val threads = Executors.newFixedThreadPool(8)
        val tarefas = List(400) { Callable { esperaDesde(iso, agora) } }

        val respostas = threads.invokeAll(tarefas).map { it.get(10, TimeUnit.SECONDS) }
        threads.shutdown()

        // Uma resposta só, repetida quatrocentas vezes: nenhuma data trocada,
        // nenhum nulo de exceção engolida.
        assertEquals(setOf(esperado), respostas.toSet())
        assertEquals(400, respostas.size)
    }

    @Test
    fun `datas diferentes nao se misturam entre as threads`() {
        val agora = 1792_000_000_000L
        val casos = listOf(
            "2026-10-17T19:00:00.000Z",
            "2026-10-17T18:00:00.000Z",
            "2026-10-17T12:00:00.000Z",
        )
        val esperado = casos.associateWith { esperaDesde(it, agora) }

        val threads = Executors.newFixedThreadPool(8)
        val tarefas = List(600) { i ->
            val iso = casos[i % casos.size]
            Callable { iso to esperaDesde(iso, agora) }
        }

        val respostas = threads.invokeAll(tarefas).map { it.get(10, TimeUnit.SECONDS) }
        threads.shutdown()

        for ((iso, espera) in respostas) {
            assertEquals("espera de $iso", esperado[iso], espera)
        }
    }

    @Test
    fun `data que nao da para ler continua devolvendo nulo`() {
        assertEquals(null, esperaDesde("ontem de noite"))
        assertEquals(null, esperaDesde(""))
    }
}
