package br.com.gestaorestaurante.maquininha

import br.com.gestaorestaurante.maquininha.api.ContaDaMesa
import br.com.gestaorestaurante.maquininha.ui.EstadoDaTela
import br.com.gestaorestaurante.maquininha.ui.Painel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Quem manda na tela da conta.
 *
 * O salão responde em segundos, e o garçom anda mais rápido do que isso: toca
 * numa mesa, desiste, toca na outra. A resposta da primeira chega depois, e
 * sem dono declarado ela escrevia por cima do que estivesse na frente do
 * garçom. O que estes testes prendem é que a resposta confere se ainda é a vez
 * dela antes de aparecer.
 */
class PainelDaContaTest {

    private fun conta(comandaId: String, falta: Long = 10_000) =
        ContaDaMesa(comandaId = comandaId, faltaEmCentavos = falta, totalEmCentavos = falta)

    @Test
    fun `a conta pedida chega e aparece`() {
        val painel = Painel().pedindoAConta("mesa-8")

        val depois = painel.comAContaQueChegou(painel.vezDaConta, conta("mesa-8"))

        assertEquals(conta("mesa-8"), depois.conta)
        assertEquals("mesa-8", depois.comandaId)
        assertEquals(false, depois.buscando)
    }

    /**
     * O caso que trocava a conta na mão do garçom: ele toca na mesa 8, a rede
     * demora, ele volta e abre a 12. A conta da 8 chegando por cima mostraria
     * o total da 8 com o nome da 12 na tela — e o toque em "Cobrar" levaria o
     * cliente da 12 a pagar o que a 8 comeu.
     */
    @Test
    fun `a conta da mesa largada nao aparece na mesa aberta depois`() {
        val largada = Painel().pedindoAConta("mesa-8")
        val agora = largada.pedindoAConta("mesa-12")

        val depois = agora.comAContaQueChegou(largada.vezDaConta, conta("mesa-8"))

        assertEquals("mesa-12", depois.comandaId)
        assertNull(depois.conta)
        // Continua esperando a conta da 12: a resposta da 8 não desliga o aviso
        // de carregando da mesa que o garçom está olhando.
        assertEquals(true, depois.buscando)
    }

    /**
     * Pedir a mesma conta duas vezes — o garçom tocou de novo porque demorou —
     * não é a mesma coisa que pedir duas contas: vale a última resposta, e a
     * primeira cai fora mesmo sendo da mesma mesa.
     */
    @Test
    fun `da mesma mesa, vale a ultima resposta`() {
        val primeira = Painel().pedindoAConta("mesa-8")
        val segunda = primeira.pedindoAConta("mesa-8")

        val velha = segunda.comAContaQueChegou(primeira.vezDaConta, conta("mesa-8", 7_000))
        assertNull(velha.conta)

        val nova = segunda.comAContaQueChegou(segunda.vezDaConta, conta("mesa-8", 9_000))
        assertEquals(9_000L, nova.conta?.faltaEmCentavos)
    }

    /**
     * O pior dos atrasos: a cobrança avulsa é a venda sem mesa — o cliente no
     * balcão. Com a conta atrasada entrando, o valor digitado passaria a ser
     * pago na conta de uma mesa que não é a dele, e aquela mesa sairia quitada
     * sem ninguém ter pago por ela.
     */
    @Test
    fun `a conta atrasada nao gruda no valor da cobranca avulsa`() {
        val largada = Painel().pedindoAConta("mesa-8")
        val avulsa = largada
            .copy(estado = EstadoDaTela.Parada, comandaId = null, valorEmCentavos = 3_000)
            .largandoAConta()

        val depois = avulsa.comAContaQueChegou(largada.vezDaConta, conta("mesa-8"))

        assertNull(depois.comandaId)
        assertNull(depois.conta)
        assertEquals(3_000L, depois.valorEmCentavos)
    }

    @Test
    fun `sem a conta, o garcom volta ao salao`() {
        val painel = Painel().pedindoAConta("mesa-8")

        val depois = painel.semAContaQueNaoVeio(painel.vezDaConta, "Sem resposta do servidor.")

        assertEquals(EstadoDaTela.Mesas, depois.estado)
        assertEquals("Sem resposta do servidor.", depois.aviso)
        assertEquals(false, depois.buscando)
    }

    /**
     * A falha que demorou a desistir não pode puxar o operador para o salão
     * com o cartão do cliente na maquininha: ele estaria digitando o valor, ou
     * esperando a senha, e a tela trocaria embaixo da mão dele.
     */
    @Test
    fun `a falha atrasada nao tira o operador do teclado`() {
        val pedida = Painel().pedindoAConta("mesa-8")
        val cobrando = pedida.copy(estado = EstadoDaTela.Parada, valorEmCentavos = 12_990)

        val depois = cobrando.semAContaQueNaoVeio(pedida.vezDaConta, "Sem resposta do servidor.")

        assertEquals(EstadoDaTela.Parada, depois.estado)
        assertEquals(12_990L, depois.valorEmCentavos)
        assertNull(depois.aviso)
    }

    /**
     * Fim de turno com uma busca no ar: a conta da mesa não pode reaparecer na
     * tela do PIN do próximo operador, que não tem direito a ver aquela mesa.
     */
    @Test
    fun `a conta atrasada nao volta depois do turno fechado`() {
        val pedida = Painel().pedindoAConta("mesa-8")
        val semTurno = pedida
            .copy(estado = EstadoDaTela.PedindoPin, conta = null)
            .largandoAConta()

        val depois = semTurno.comAContaQueChegou(pedida.vezDaConta, conta("mesa-8"))

        assertNull(depois.conta)
        assertEquals(EstadoDaTela.PedindoPin, depois.estado)
    }
/**
     * A falha da mesa largada não pode derrubar a mesa seguinte: o garçom
     * estaria esperando a conta da 12, e a desistência da 8 o jogaria para o
     * salão como se a 12 é que não tivesse vindo.
     */
    @Test
    fun `a falha da mesa largada nao derruba a mesa seguinte`() {
        val largada = Painel().pedindoAConta("mesa-8")
        val agora = largada.pedindoAConta("mesa-12")

        val depois = agora.semAContaQueNaoVeio(largada.vezDaConta, "Sem resposta do servidor.")

        assertEquals(EstadoDaTela.Conta, depois.estado)
        assertEquals(true, depois.buscando)
        assertNull(depois.aviso)
    }
}
