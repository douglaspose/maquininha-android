package br.com.gestaorestaurante.maquininha

import br.com.gestaorestaurante.maquininha.api.ItemDoCardapio
import br.com.gestaorestaurante.maquininha.api.MesaAberta
import br.com.gestaorestaurante.maquininha.api.RespostaDeMesas
import br.com.gestaorestaurante.maquininha.config.Configuracao
import br.com.gestaorestaurante.maquininha.ui.EstadoDaTela
import br.com.gestaorestaurante.maquininha.ui.LinhaDoCarrinho
import br.com.gestaorestaurante.maquininha.ui.Painel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O salão se refaz sozinho a cada trinta segundos, e isso não pode aparecer na
 * tela de quem está trabalhando.
 *
 * A ronda escrevia nos mesmos campos de qualquer operação: a que falhava punha
 * banner vermelho no meio da conta, do cardápio e do cancelamento — onde a
 * tela do PIN do gerente se limpa sozinha quando o aviso muda —, e a que dava
 * certo apagava o erro de uma operação de verdade antes de o garçom ler.
 */
class RondaDoSalaoTest {

    private val salao = RespostaDeMesas(
        unidade = "Jantinha do Lipão",
        mesas = listOf(MesaAberta(comandaId = "c1", numero = 9, mesa = "9")),
        podeAbrir = true,
        podeReceber = true,
    )

    private val comTurno = Configuracao(
        servidor = "https://gestao.exemplo.com.br",
        token = "token-do-aparelho",
        operador = "cracha",
    )

    @Test
    fun `a ronda troca os dados do salao sem falar com a tela da conta`() {
        val naConta = Painel(estado = EstadoDaTela.Conta, buscando = true, aviso = "Item esgotado.")

        val depois = naConta.comOSalao(salao, deFundo = true)

        // Os dados entram: o salão está atualizado quando o garçom voltar.
        assertEquals(1, depois.mesas.size)
        // Mas o recado da operação dele fica, e o "carregando" da conta também.
        assertEquals("Item esgotado.", depois.aviso)
        assertEquals(true, depois.buscando)
    }

    @Test
    fun `quem esta olhando o salao ve a ronda terminar`() {
        val noSalao = Painel(estado = EstadoDaTela.Mesas, buscando = true, aviso = "Sem resposta do servidor.")

        val depois = noSalao.comOSalao(salao, deFundo = true)

        assertEquals(false, depois.buscando)
        assertNull(depois.aviso)
    }

    /**
     * O caso mais feio: a tela do cancelamento limpa o PIN do gerente quando o
     * aviso muda. Uma ronda que falhasse no meio da digitação apagava o que
     * ele estava digitando, na frente do cliente.
     */
    @Test
    fun `a falha da ronda nao poe banner vermelho no cancelamento`() {
        val cancelando = Painel(estado = EstadoDaTela.Cancelando, pedindoLiberacao = true)

        val depois = cancelando.semOSalao("Sem resposta do servidor.", deFundo = true)

        assertNull(depois.aviso)
        assertEquals(cancelando, depois)
    }

    @Test
    fun `no salao, a falha da ronda aparece`() {
        val noSalao = Painel(estado = EstadoDaTela.Mesas, buscando = true)

        val depois = noSalao.semOSalao("Sem resposta do servidor.", deFundo = true)

        assertEquals("Sem resposta do servidor.", depois.aviso)
        assertEquals(false, depois.buscando)
    }

    /** O toque em "Atualizar" é pedido: ele fala, esteja onde estiver. */
    @Test
    fun `a atualizacao pedida pelo operador sempre responde na tela`() {
        val naConta = Painel(estado = EstadoDaTela.Conta, buscando = true)

        val depois = naConta.semOSalao("Sem resposta do servidor.", deFundo = false)

        assertEquals("Sem resposta do servidor.", depois.aviso)
        assertEquals(false, depois.buscando)
    }
}

/**
 * O carrinho é da mesa, e o turno é de quem digitou o PIN.
 */
class CarrinhoETurnoTest {

    private val item = ItemDoCardapio(
        cardapioItemId = "cardapio-1",
        produtoId = "produto-1",
        titulo = "Costela no Bafo",
        precoEmCentavos = 11_990,
    )

    private fun comCarrinho(comandaId: String) = Painel(
        configuracao = Configuracao(servidor = "s", token = "t", operador = "cracha"),
        estado = EstadoDaTela.Conta,
        comandaId = comandaId,
        carrinho = listOf(LinhaDoCarrinho(item = item, quantidade = 2)),
    )

    /**
     * O que apagava a rodada montada: abrir o cardápio zerava o carrinho, e o
     * garçom que voltava à conta só para conferir o total perdia tudo.
     */
    @Test
    fun `voltar para a mesma conta nao apaga a rodada em montagem`() {
        val painel = comCarrinho("mesa-9")

        val depois = painel.pedindoAConta("mesa-9")

        assertEquals(1, depois.carrinho.size)
        assertEquals(2, depois.carrinho[0].quantidade)
    }

    @Test
    fun `trocar de mesa limpa o carrinho da anterior`() {
        val painel = comCarrinho("mesa-9")

        val depois = painel.pedindoAConta("mesa-12")

        assertTrue(depois.carrinho.isEmpty())
    }

    /**
     * Fim de turno: o próximo operador digita o PIN dele e não pode encontrar
     * a conta, o carrinho nem o recado de quem saiu.
     */
    @Test
    fun `fechar o turno leva junto a conta, o carrinho e o cardapio`() {
        val painel = comCarrinho("mesa-9").copy(
            cardapio = emptyList(),
            recado = "Conta pedida. A folha está saindo.",
            conta = null,
            valorEmCentavos = 5_000,
            pendentesDeEnvio = 2,
        )

        val depois = painel.semOTurno()

        assertEquals(EstadoDaTela.PedindoPin, depois.estado)
        assertNull(depois.comandaId)
        assertTrue(depois.carrinho.isEmpty())
        assertNull(depois.recado)
        assertEquals(0, depois.valorEmCentavos)
        assertTrue(depois.mesas.isEmpty())
        // O que é do aparelho fica: configuração e o que ainda não foi enviado.
        assertEquals(painel.configuracao, depois.configuracao)
        assertEquals(2, depois.pendentesDeEnvio)
    }

    /** Busca de conta no ar não volta na tela do operador seguinte. */
    @Test
    fun `fechar o turno derruba a vez da conta`() {
        val pedida = Painel().pedindoAConta("mesa-9")
        val semTurno = pedida.semOTurno()

        val depois = semTurno.comAContaQueChegou(pedida.vezDaConta, contaVazia())

        assertNull(depois.conta)
        assertEquals(EstadoDaTela.PedindoPin, depois.estado)
    }

    private fun contaVazia() =
        br.com.gestaorestaurante.maquininha.api.ContaDaMesa(comandaId = "mesa-9")
}
