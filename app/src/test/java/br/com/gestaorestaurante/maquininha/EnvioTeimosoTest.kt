package br.com.gestaorestaurante.maquininha

import br.com.gestaorestaurante.maquininha.api.PagamentoParaEnviar
import br.com.gestaorestaurante.maquininha.api.RespostaDoPagamento
import br.com.gestaorestaurante.maquininha.pagamento.DepositoDePendentes
import br.com.gestaorestaurante.maquininha.pagamento.EnvioTeimoso
import br.com.gestaorestaurante.maquininha.pagamento.PendenteDeEnvio
import br.com.gestaorestaurante.maquininha.pagamento.TurnoVencido
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * O caminho do dinheiro depois do "aprovado".
 *
 * Quando a credenciadora aprova, o cartão do cliente já foi debitado. Se o
 * servidor não ficar sabendo, a conta continua aberta no salão e alguém cobra
 * de novo. O que estes testes prendem é a ordem — guardar antes de tentar — e
 * o que acontece quando o aplicativo morre no meio da tentativa, que é o caso
 * em que o pagamento sumia.
 */
class EnvioTeimosoTest {

    /** A fila em memória, no lugar do DataStore. */
    private class DepositoDeTeste : DepositoDePendentes {
        var fila = listOf<PendenteDeEnvio>()
        override suspend fun ler() = fila
        override suspend fun gravar(fila: List<PendenteDeEnvio>) {
            this.fila = fila
        }

        /** As referências na fila, que é o que os testes conferem. */
        val referencias get() = fila.map { it.pagamento.referencia }
    }

    private fun pagamento(referencia: String) = PagamentoParaEnviar(
        comandaId = "comanda-1",
        referencia = referencia,
        forma = "CREDITO",
        valorEmCentavos = 5000,
    )

    private fun pendente(referencia: String, cracha: String = "cracha-do-caixa") =
        PendenteDeEnvio(pagamento(referencia), cracha)

    private val recebido = RespostaDoPagamento(ok = true, quitada = true, faltaEmCentavos = 0)

    @Test
    fun `servidor recebeu, a fila fica vazia`() = runTest {
        val deposito = DepositoDeTeste()
        val envio = EnvioTeimoso(deposito) { _, _ -> recebido }

        val resposta = envio.enviar(pagamento("venda-1"), "cracha-do-caixa")

        assertEquals(true, resposta?.quitada)
        assertEquals(emptyList<PendenteDeEnvio>(), deposito.fila)
    }

    @Test
    fun `rede fora, o pagamento fica na fila e a tela sabe disso`() = runTest {
        val deposito = DepositoDeTeste()
        val envio = EnvioTeimoso(deposito) { _, _ -> throw IOException("sem rede") }

        val resposta = envio.enviar(pagamento("venda-2"), "cracha-do-caixa")

        // Nulo é "não foi": a conta continua aberta no salão até o escoamento.
        assertNull(resposta)
        assertEquals(listOf("venda-2"), deposito.referencias)
    }

    /**
     * O caso que fazia o pagamento sumir.
     *
     * A tentativa demora dezenas de segundos justamente quando a rede está
     * ruim. Se o aplicativo for fechado nesse intervalo — Back na tela raiz,
     * deslizar dos recentes —, o escopo é cancelado no meio. Guardando antes,
     * o cancelamento não leva o dinheiro junto.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `aplicativo fechado no meio do envio nao perde o pagamento`() = runTest {
        val deposito = DepositoDeTeste()
        val naRede = CompletableDeferred<Unit>()
        val envio = EnvioTeimoso(deposito) { _, _ ->
            naRede.complete(Unit)
            // Nunca responde: é a rede pendurada, não a rede recusando.
            CompletableDeferred<RespostaDoPagamento>().await()
        }

        val tarefa = launch { envio.enviar(pagamento("venda-3"), "cracha-do-caixa") }
        naRede.await()
        tarefa.cancel()

        assertEquals(listOf("venda-3"), deposito.referencias)
    }

    @Test
    fun `o escoamento manda o que ficou para tras`() = runTest {
        val deposito = DepositoDeTeste()
        deposito.fila = listOf(pendente("venda-4"), pendente("venda-5"))
        val mandados = mutableListOf<String>()
        val envio = EnvioTeimoso(deposito) { pagamento, _ ->
            mandados += pagamento.referencia
            recebido
        }

        val enviados = envio.escoar()

        assertEquals(2, enviados)
        assertEquals(listOf("venda-4", "venda-5"), mandados)
        assertEquals(emptyList<PendenteDeEnvio>(), deposito.fila)
    }

    @Test
    fun `rede cai no meio do escoamento, o resto continua na fila`() = runTest {
        val deposito = DepositoDeTeste()
        deposito.fila = listOf(pendente("venda-6"), pendente("venda-7"))
        val envio = EnvioTeimoso(deposito) { pagamento, _ ->
            if (pagamento.referencia == "venda-7") throw IOException("caiu de novo")
            recebido
        }

        val enviados = envio.escoar()

        // O que saiu, saiu; o que não saiu espera a próxima volta da rede, em
        // vez de ser dado como enviado.
        assertEquals(1, enviados)
        assertEquals(listOf("venda-7"), deposito.referencias)
    }

    @Test
    fun `o mesmo pagamento nao entra duas vezes na fila`() = runTest {
        val deposito = DepositoDeTeste()
        val envio = EnvioTeimoso(deposito) { _, _ -> throw IOException("sem rede") }

        envio.enviar(pagamento("venda-8"), "cracha-do-caixa")
        envio.enviar(pagamento("venda-8"), "cracha-do-caixa")

        assertEquals(1, deposito.fila.size)
        assertEquals(1, envio.quantasPendentes())
    }

    @Test
    fun `insiste algumas vezes antes de desistir da rede`() = runTest {
        val deposito = DepositoDeTeste()
        var tentativas = 0
        val envio = EnvioTeimoso(deposito) { _, _ ->
            tentativas++
            // Na terceira, a oscilação do wi-fi passou.
            if (tentativas < 3) throw IOException("instável") else recebido
        }

        val resposta = envio.enviar(pagamento("venda-9"), "cracha-do-caixa")

        assertTrue(resposta != null)
        assertEquals(3, tentativas)
        assertEquals(emptyList<PendenteDeEnvio>(), deposito.fila)
    }
/**
     * A venda entra no servidor em nome de quem apresenta o crachá. Escoada
     * com o crachá de quem estiver no aparelho quando a rede voltar, ela
     * entraria no nome da pessoa errada — e é por esse nome que a casa confere
     * o caixa no fim do turno.
     */
    @Test
    fun `o pagamento sai com o cracha de quem cobrou, nao com o de quem esta no aparelho`() =
        runTest {
            val deposito = DepositoDeTeste()
            deposito.fila = listOf(pendente("venda-10", cracha = "cracha-do-turno-da-noite"))
            var crachaUsado: String? = null
            val envio = EnvioTeimoso(deposito) { _, cracha ->
                crachaUsado = cracha
                recebido
            }

            envio.escoar()

            assertEquals("cracha-do-turno-da-noite", crachaUsado)
        }

    @Test
    fun `cracha vencido segura so o pagamento dele, e os outros saem`() = runTest {
        val deposito = DepositoDeTeste()
        deposito.fila = listOf(
            pendente("venda-11", cracha = "cracha-vencido"),
            pendente("venda-12", cracha = "cracha-bom"),
        )
        val envio = EnvioTeimoso(deposito) { _, cracha ->
            if (cracha == "cracha-vencido") throw TurnoVencido("turno encerrado") else recebido
        }

        val enviados = envio.escoar()

        // O vencido espera a pessoa dele voltar: entrar no nome de outra é
        // pior do que esperar. O resto não fica preso atrás dele.
        assertEquals(1, enviados)
        assertEquals(listOf("venda-11"), deposito.referencias)
    }

    /**
     * Atualizar o aplicativo com a fila cheia não pode jogar fora dinheiro já
     * cobrado: o pendente sem crachá sai com o turno de agora, que é o que
     * acontecia antes desta versão de qualquer jeito.
     */
    @Test
    fun `pendente guardado pela versao antiga ainda sai`() = runTest {
        val deposito = DepositoDeTeste()
        deposito.fila = listOf(PendenteDeEnvio(pagamento("venda-13")))
        var crachaUsado: String? = "não mexeram"
        val envio = EnvioTeimoso(deposito) { _, cracha ->
            crachaUsado = cracha
            recebido
        }

        val enviados = envio.escoar()

        assertEquals(1, enviados)
        // Nulo quer dizer "use o crachá do aparelho".
        assertNull(crachaUsado)
        assertEquals(emptyList<PendenteDeEnvio>(), deposito.fila)
    }
}
