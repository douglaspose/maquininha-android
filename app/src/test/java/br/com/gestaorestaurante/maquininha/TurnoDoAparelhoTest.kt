package br.com.gestaorestaurante.maquininha

import br.com.gestaorestaurante.maquininha.api.ContaDaMesa
import br.com.gestaorestaurante.maquininha.config.Configuracao
import br.com.gestaorestaurante.maquininha.ui.EstadoDaTela
import br.com.gestaorestaurante.maquininha.ui.Painel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Quem pode chegar ao teclado da cobrança, e o que a tela conta de si.
 *
 * O aparelho fica em cima do balcão a noite inteira, e os ajustes são abertos
 * de propósito — é na tela do PIN que se conserta um token errado, quando não
 * há turno para pedir. O que não pode é a porta dos ajustes dar no teclado da
 * cobrança, nem o token da casa ficar à mostra para quem passar por ali.
 */
class TurnoDoAparelhoTest {

    private val semTurno = Configuracao(
        servidor = "https://gestao.exemplo.com.br",
        token = "o7cZiIqlgYWTXeGm3tizN4yW4lhmCWeO",
    )
    private val comTurno = semTurno.copy(operador = "cracha-do-caixa", operadorNome = "Pajé")
    private val semToken = Configuracao()

    private fun nosAjustes(config: Configuracao) =
        Painel(configuracao = config, estado = EstadoDaTela.Configurando)

    private val conta = ContaDaMesa(comandaId = "mesa-8", faltaEmCentavos = 12_990)

    /**
     * O buraco: Ajustes › Voltar caía sempre no teclado. Com os ajustes
     * abertos na tela do PIN, qualquer um que pegasse o aparelho chegava à
     * cobrança por esse caminho — e a credenciadora aprova sem perguntar de
     * quem é o turno.
     */
    @Test
    fun `sair dos ajustes sem turno volta para o PIN, nao para o teclado`() {
        val depois = nosAjustes(semTurno).saindoDosAjustes()

        assertEquals(EstadoDaTela.PedindoPin, depois.estado)
    }

    @Test
    fun `sair dos ajustes com turno aberto volta para o salao`() {
        val depois = nosAjustes(comTurno).saindoDosAjustes()

        assertEquals(EstadoDaTela.Mesas, depois.estado)
    }

    /**
     * Aparelho recém-tirado da caixa: sair dos ajustes não tem para onde ir —
     * sem token ele não fala com o servidor, e o PIN não teria com quem ser
     * conferido.
     */
    @Test
    fun `sem token, sair dos ajustes fica nos ajustes`() {
        val depois = nosAjustes(semToken).saindoDosAjustes()

        assertEquals(EstadoDaTela.Configurando, depois.estado)
    }

    @Test
    fun `sem turno, a venda de balcao manda digitar o PIN`() {
        val depois = Painel(configuracao = semTurno).naCobrancaAvulsa()

        assertEquals(EstadoDaTela.PedindoPin, depois.estado)
        assertNotNull(depois.aviso)
    }

    @Test
    fun `com turno, a venda de balcao abre o teclado zerado`() {
        val depois = Painel(configuracao = comTurno).naCobrancaAvulsa()

        assertEquals(EstadoDaTela.Parada, depois.estado)
        assertEquals(0, depois.valorEmCentavos)
        // Sem conta: é a venda que não passou por mesa nenhuma.
        assertNull(depois.comandaId)
    }

    /**
     * O turno vale doze horas e a tela da conta fica aberta no bolso do
     * garçom. Vencido no meio do atendimento, cobrar dali passaria o cartão em
     * nome de ninguém — e o caixa da noite fecharia com dinheiro sem dono.
     */
    @Test
    fun `turno vencido com a conta na tela nao cobra`() {
        val depois = Painel(configuracao = semTurno, conta = conta).naCobrancaDaConta(conta, tudo = true)

        assertEquals(EstadoDaTela.PedindoPin, depois.estado)
        assertEquals(0, depois.valorEmCentavos)
    }

    @Test
    fun `com turno, cobrar da conta leva o que falta`() {
        val depois = Painel(configuracao = comTurno, conta = conta).naCobrancaDaConta(conta, tudo = true)

        assertEquals(EstadoDaTela.Parada, depois.estado)
        assertEquals(12_990, depois.valorEmCentavos)
        assertEquals("mesa-8", depois.comandaId)
    }

    /**
     * O token é a credencial da casa: com ele, outro aparelho entra como este
     * e puxa a fila de impressão — os papéis que a cozinha espera sairiam no
     * bolso de quem copiou. A tela mostra só o fim, que é o que serve para
     * conferir num 401.
     */
    @Test
    fun `o token guardado aparece so pelo fim`() {
        val mascarado = semTurno.tokenMascarado

        assertTrue(mascarado.endsWith("CWeO"))
        assertEquals(semTurno.token.length, mascarado.length)
        // Nada do começo vaza: só os quatro últimos são legíveis.
        assertEquals("•".repeat(28) + "CWeO", mascarado)
    }

    @Test
    fun `token curto nao mostra nem o fim`() {
        val mascarado = Configuracao(token = "abc123").tokenMascarado

        assertEquals("••••••", mascarado)
    }

    @Test
    fun `sem token, nao ha o que mostrar`() {
        assertEquals("", Configuracao().tokenMascarado)
    }
}
