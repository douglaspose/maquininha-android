package br.com.gestaorestaurante.maquininha

import br.com.gestaorestaurante.maquininha.api.PagamentoParaEnviar
import br.com.gestaorestaurante.maquininha.api.RespostaDoPagamento
import br.com.gestaorestaurante.maquininha.pagamento.DepositoDePendentes
import br.com.gestaorestaurante.maquininha.pagamento.EnvioTeimoso
import br.com.gestaorestaurante.maquininha.pagamento.EsperaACasa
import br.com.gestaorestaurante.maquininha.pagamento.PendenteDeEnvio
import br.com.gestaorestaurante.maquininha.pagamento.RecusaDefinitiva
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
// --- o que o servidor recusa de vez ---

    /**
     * O que travava a noite inteira.
     *
     * Erro de sistema do servidor — valor acima do que a coluna guarda,
     * referência comprida demais — chegava aqui como "falhou", igual a uma
     * queda de rede: a fila reenviava a mesma venda para sempre e parava na
     * cabeça, segurando todas as de trás. Agora ela sai da fila de tentativas
     * marcada, e as outras saem.
     */
    @Test
    fun `recusa definitiva sai da fila de tentativas, e as de tras saem`() = runTest {
        val deposito = DepositoDeTeste()
        deposito.fila = listOf(pendente("venda-ruim"), pendente("venda-boa"))
        val envio = EnvioTeimoso(deposito) { pagamento, _ ->
            if (pagamento.referencia == "venda-ruim") {
                throw RecusaDefinitiva("Valor alto demais para uma venda.")
            }
            recebido
        }

        val enviados = envio.escoar()

        assertEquals(1, enviados)
        assertEquals(0, envio.quantasPendentes())
        assertEquals(1, envio.quantasRecusadas())
        // Guardada, e com o motivo: o cartão foi debitado e alguém vai atrás
        // com o comprovante na mão.
        assertEquals(listOf("venda-ruim"), deposito.referencias)
        assertEquals("Valor alto demais para uma venda.", deposito.fila[0].recusa)
    }

    @Test
    fun `a recusada nao e tentada de novo no proximo escoamento`() = runTest {
        val deposito = DepositoDeTeste()
        deposito.fila = listOf(pendente("venda-ruim"))
        var tentativas = 0
        val envio = EnvioTeimoso(deposito) { _, _ ->
            tentativas++
            throw RecusaDefinitiva("Referência comprida demais.")
        }

        envio.escoar()
        envio.escoar()
        envio.escoar()

        assertEquals(1, tentativas)
    }

    /** Com o cliente na frente, insistir três vezes no que não muda é só demora. */
    @Test
    fun `recusa definitiva na cobranca nao gasta as tres tentativas`() = runTest {
        val deposito = DepositoDeTeste()
        var tentativas = 0
        val envio = EnvioTeimoso(deposito) { _, _ ->
            tentativas++
            throw RecusaDefinitiva("O servidor recusou esta venda (400).")
        }

        val resposta = envio.enviar(pagamento("venda-14"), "cracha-do-caixa")

        assertNull(resposta)
        assertEquals(1, tentativas)
        assertEquals(1, envio.quantasRecusadas())
        assertEquals(0, envio.quantasPendentes())
    }

    /**
     * "Nenhum caixa aberto" é o momento, não o corpo: muda sozinho quando
     * alguém abrir o caixa. Esta espera a casa — e não segura as outras.
     */
    @Test
    fun `recusa da casa espera a proxima volta, sem travar as outras`() = runTest {
        val deposito = DepositoDeTeste()
        deposito.fila = listOf(pendente("venda-15"), pendente("venda-16"))
        val envio = EnvioTeimoso(deposito) { pagamento, _ ->
            if (pagamento.referencia == "venda-15") {
                throw EsperaACasa("Nenhum caixa aberto. Abra o caixa antes de receber.")
            }
            recebido
        }

        val enviados = envio.escoar()

        assertEquals(1, enviados)
        // Continua pendente, e não recusada: ela ainda vai sair.
        assertEquals(1, envio.quantasPendentes())
        assertEquals(0, envio.quantasRecusadas())
        assertEquals(listOf("venda-15"), deposito.referencias)
    }

    @Test
    fun `aberto o caixa, a que esperava a casa sai`() = runTest {
        val deposito = DepositoDeTeste()
        deposito.fila = listOf(pendente("venda-17"))
        var caixaAberto = false
        val envio = EnvioTeimoso(deposito) { _, _ ->
            if (!caixaAberto) throw EsperaACasa("Nenhum caixa aberto.") else recebido
        }

        assertEquals(0, envio.escoar())
        caixaAberto = true
        assertEquals(1, envio.escoar())
        assertEquals(0, envio.quantasPendentes())
    }
/**
     * A recusada fica no disco, e a venda nova que chega depois dela precisa
     * passar por cima: é o caso misto — fila com uma recusada guardada e uma
     * boa esperando — que prova que a marca é respeitada, e não o atalho de
     * "a fila só tem recusadas".
     */
    @Test
    fun `a venda nova passa por cima da recusada guardada`() = runTest {
        val deposito = DepositoDeTeste()
        deposito.fila = listOf(
            pendente("venda-recusada").copy(recusa = "O servidor recusou esta venda (400)."),
            pendente("venda-nova"),
        )
        val mandados = mutableListOf<String>()
        val envio = EnvioTeimoso(deposito) { pagamento, _ ->
            mandados += pagamento.referencia
            recebido
        }

        val enviados = envio.escoar()

        assertEquals(1, enviados)
        // A recusada nem foi tentada.
        assertEquals(listOf("venda-nova"), mandados)
        assertEquals(listOf("venda-recusada"), deposito.referencias)
        assertEquals(1, envio.quantasRecusadas())
    }
// --- a lista das recusadas, e a baixa ---

    /**
     * A tela das recusadas existe para a casa conferir contra o extrato da
     * adquirente: o que ela precisa é a venda mais nova em cima, com valor,
     * hora, NSU e o nome de quem cobrou.
     */
    @Test
    fun `a lista traz so as recusadas, da mais nova para a mais velha`() = runTest {
        val deposito = DepositoDeTeste()
        deposito.fila = listOf(
            pendente("venda-velha").copy(recusa = "Conta não encontrada.", cobradaEm = 1_000),
            pendente("venda-pendente"),
            pendente("venda-nova").copy(recusa = "Valor alto demais.", cobradaEm = 9_000),
        )
        val envio = EnvioTeimoso(deposito) { _, _ -> recebido }

        val lista = envio.recusadas()

        assertEquals(listOf("venda-nova", "venda-velha"), lista.map { it.pagamento.referencia })
        assertEquals("Valor alto demais.", lista[0].recusa)
    }

    @Test
    fun `a cobranca guarda quem cobrou e quando`() = runTest {
        val deposito = DepositoDeTeste()
        val envio = EnvioTeimoso(deposito) { _, _ ->
            throw RecusaDefinitiva("O servidor recusou esta venda (400).")
        }

        envio.enviar(pagamento("venda-18"), "cracha", operadorNome = "Pajé", agoraEmMillis = 7_777)

        val recusada = envio.recusadas().single()
        assertEquals("Pajé", recusada.operadorNome)
        assertEquals(7_777, recusada.cobradaEm)
    }

    /** Resolvida na retaguarda, ela sai do aparelho. */
    @Test
    fun `dar baixa tira a recusada da fila`() = runTest {
        val deposito = DepositoDeTeste()
        deposito.fila = listOf(
            pendente("venda-19").copy(recusa = "Conta não encontrada."),
            pendente("venda-20").copy(recusa = "Valor alto demais."),
        )
        val envio = EnvioTeimoso(deposito) { _, _ -> recebido }

        envio.darBaixa("venda-19")

        assertEquals(listOf("venda-20"), deposito.referencias)
        assertEquals(1, envio.quantasRecusadas())
    }

    /**
     * Pendente não some por engano: ela ainda vai ser enviada, e apagá-la
     * daqui seria jogar fora dinheiro que o servidor nunca soube que entrou.
     */
    @Test
    fun `dar baixa nao apaga uma venda que ainda espera a rede`() = runTest {
        val deposito = DepositoDeTeste()
        deposito.fila = listOf(pendente("venda-21"))
        val envio = EnvioTeimoso(deposito) { _, _ -> recebido }

        envio.darBaixa("venda-21")

        assertEquals(listOf("venda-21"), deposito.referencias)
        assertEquals(1, envio.quantasPendentes())
    }
}
