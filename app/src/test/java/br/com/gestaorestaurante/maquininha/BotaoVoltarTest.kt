package br.com.gestaorestaurante.maquininha

import br.com.gestaorestaurante.maquininha.config.Configuracao
import br.com.gestaorestaurante.maquininha.pagamento.ResultadoDaCobranca
import br.com.gestaorestaurante.maquininha.ui.AcaoDoBack
import br.com.gestaorestaurante.maquininha.ui.EstadoDaTela
import br.com.gestaorestaurante.maquininha.ui.Painel
import br.com.gestaorestaurante.maquininha.ui.acaoDoBack
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * O botão Voltar do Android, tela a tela.
 *
 * Nada ligava esse botão ao aplicativo: ele fechava a tela, qualquer que
 * fosse. Nas provas deste repositório aconteceu duas vezes — uma na tela do
 * resultado, logo depois de uma venda aprovada, e outra no teclado da
 * cobrança. Num salão, é a venda sumindo da vista com o cliente na frente.
 */
class BotaoVoltarTest {

    private val completa = Configuracao(
        servidor = "https://gestao.exemplo.com.br",
        token = "o7cZiIqlgYWTXeGm3tizN4yW4lhmCWeO",
        operador = "cracha-do-caixa",
    )

    private fun em(estado: EstadoDaTela, config: Configuracao = completa) =
        acaoDoBack(Painel(configuracao = config, estado = estado))

    @Test
    fun `nas telas raiz o Back nao fecha o aplicativo`() {
        assertEquals(AcaoDoBack.NADA, em(EstadoDaTela.Mesas))
        assertEquals(AcaoDoBack.NADA, em(EstadoDaTela.PedindoPin))
        assertEquals(AcaoDoBack.NADA, em(EstadoDaTela.Abrindo))
    }

    /**
     * A maquininha está com o cartão do cliente, e a credenciadora no meio de
     * uma conversa que não é nossa para cancelar pela metade — a mesma razão
     * que já cala o teclado durante a transação.
     */
    @Test
    fun `durante a cobranca o Back nao interrompe`() {
        assertEquals(AcaoDoBack.NADA, em(EstadoDaTela.Cobrando("Autorizando")))
    }

    @Test
    fun `nas telas de dentro o Back volta uma tela`() {
        assertEquals(AcaoDoBack.TELA_ANTERIOR, em(EstadoDaTela.Conta))
        assertEquals(AcaoDoBack.TELA_ANTERIOR, em(EstadoDaTela.Cardapio))
        assertEquals(AcaoDoBack.TELA_ANTERIOR, em(EstadoDaTela.EscolhendoMesa))
        assertEquals(AcaoDoBack.TELA_ANTERIOR, em(EstadoDaTela.AbrindoMesa))
        assertEquals(AcaoDoBack.TELA_ANTERIOR, em(EstadoDaTela.EscolhendoDestino))
        assertEquals(AcaoDoBack.TELA_ANTERIOR, em(EstadoDaTela.Cancelando))
        assertEquals(AcaoDoBack.TELA_ANTERIOR, em(EstadoDaTela.Parada))
    }

    /** A tela do resultado se encerra como o botão dela: venda encerrada. */
    @Test
    fun `na tela do resultado o Back encerra a venda`() {
        val terminada = EstadoDaTela.Terminada(
            resultado = ResultadoDaCobranca.Recusada("Saldo insuficiente", "51"),
            enviado = true,
        )

        assertEquals(AcaoDoBack.ENCERRAR_VENDA, em(terminada))
    }

    @Test
    fun `nos ajustes o Back fecha os ajustes`() {
        assertEquals(AcaoDoBack.FECHAR_AJUSTES, em(EstadoDaTela.Configurando))
    }

    /**
     * Aparelho recém-tirado da caixa: sem token ele não fala com ninguém, e
     * não há tela nenhuma atrás dos ajustes para onde voltar.
     */
    @Test
    fun `sem token, o Back fica nos ajustes`() {
        assertEquals(
            AcaoDoBack.NADA,
            em(EstadoDaTela.Configurando, config = Configuracao())
        )
    }
}
