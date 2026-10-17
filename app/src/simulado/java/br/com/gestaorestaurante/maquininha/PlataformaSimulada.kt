package br.com.gestaorestaurante.maquininha

import android.content.Context
import android.util.Log
import br.com.gestaorestaurante.maquininha.impressao.Cupom
import br.com.gestaorestaurante.maquininha.impressao.Impressora
import br.com.gestaorestaurante.maquininha.pagamento.AndamentoDaCobranca
import br.com.gestaorestaurante.maquininha.pagamento.Cobranca
import br.com.gestaorestaurante.maquininha.pagamento.ProvedorDePagamento
import br.com.gestaorestaurante.maquininha.pagamento.ResultadoDaCobranca
import br.com.gestaorestaurante.maquininha.pagamento.TransacaoAprovada
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * A maquininha de mentira.
 *
 * Existe para que o aplicativo inteiro — fila de impressão, envio teimoso,
 * telas, conciliação — possa ser desenvolvido e testado no emulador, sem
 * aparelho, sem SDK e sem homologação. É nela que se roda o dia a dia; a
 * credenciadora de verdade entra só no fim.
 *
 * Ela também é onde se ensaia o que dá errado: recusa, cancelamento e queda
 * no meio da transação acontecem aqui de propósito, porque são os caminhos
 * que ninguém testa com cartão de verdade e que aparecem no primeiro sábado
 * cheio.
 */
object PlataformaAtual : Plataforma {

    override val credenciadora = "Simulado"

    override fun pagamento(contexto: Context): ProvedorDePagamento = ProvedorSimulado()

    override fun impressora(contexto: Context): Impressora = ImpressoraSimulada()
}

class ProvedorSimulado : ProvedorDePagamento {

    override val nome = "Simulado"

    override suspend fun preparar(): Boolean {
        delay(300)
        return true
    }

    override suspend fun cobrar(
        cobranca: Cobranca,
        aoAndar: (AndamentoDaCobranca) -> Unit,
    ): ResultadoDaCobranca {
        // Os tempos são parecidos com os de verdade: uma transação de cartão
        // leva de 6 a 12 segundos, e é esse tempo que faz a tela de espera
        // importar tanto.
        aoAndar(AndamentoDaCobranca("Aproxime, insira ou passe o cartão"))
        delay(2500)
        aoAndar(AndamentoDaCobranca("Lendo o cartão"))
        delay(1500)
        aoAndar(AndamentoDaCobranca("Autorizando"))
        delay(2500)

        /*
         * R$ 6,66 estoura, em vez de responder.
         *
         * Credenciadora de verdade às vezes não devolve resultado nenhum: o
         * SDK lança, o telefone perde a conexão com o pinpad, a biblioteca
         * nativa morre. A tela de cobrança não tem botão, então uma exceção
         * aqui prendia o aparelho nela. Este valor existe para provar na
         * bancada que a tela sempre sai — e só existe neste flavor.
         */
        if (cobranca.valorEmCentavos == 666L) {
            throw IllegalStateException("A credenciadora não respondeu (bancada).")
        }

        /*
         * Valores terminados em 00 sempre aprovam — é o que se usa no teste
         * de bancada, para não ficar refém do sorteio. O resto segue a
         * proporção de um dia comum: quase tudo aprova, uma em vinte recusa.
         */
        val sorte = if (cobranca.valorEmCentavos % 100L == 0L) 0 else Random.nextInt(100)
        return when {
            sorte < 90 -> ResultadoDaCobranca.Aprovada(
                TransacaoAprovada(
                    referencia = cobranca.referencia,
                    identificadorNaCredenciadora = "SIM" + System.currentTimeMillis(),
                    valorEmCentavos = cobranca.valorEmCentavos,
                    forma = cobranca.forma,
                    parcelas = cobranca.parcelas,
                    bandeira = listOf("VISA", "MASTERCARD", "ELO").random(),
                    autorizacao = Random.nextInt(100000, 999999).toString(),
                    finalDoCartao = Random.nextInt(1000, 9999).toString(),
                    viaDoCliente = comprovante(cobranca),
                    viaDaLoja = comprovante(cobranca),
                )
            )
            sorte < 96 -> ResultadoDaCobranca.Recusada("Saldo insuficiente", "51")
            sorte < 98 -> ResultadoDaCobranca.Cancelada
            else -> ResultadoDaCobranca.Erro("Sem comunicação com a adquirente")
        }
    }

    override suspend fun cancelar(transacao: TransacaoAprovada): ResultadoDaCobranca {
        delay(2000)
        return ResultadoDaCobranca.Aprovada(transacao)
    }

    private fun comprovante(cobranca: Cobranca) = buildString {
        appendLine("       COMPROVANTE (SIMULADO)")
        appendLine()
        appendLine("VALOR: " + cobranca.valorEmCentavos / 100 + "," +
            (cobranca.valorEmCentavos % 100).toString().padStart(2, '0'))
        appendLine("FORMA: " + cobranca.forma.rotulo)
        appendLine("REF..: " + cobranca.referencia.take(8))
        appendLine()
        appendLine("    TRANSACAO SEM VALOR FISCAL")
    }
}

class ImpressoraSimulada : Impressora {

    // 32 é a largura da térmica embutida numa maquininha de 58mm — o mesmo
    // número do aparelho de verdade, para o adaptador de largura ser exercido
    // no emulador exatamente como será em campo.
    override val colunas = 32

    override suspend fun imprimir(cupom: Cupom): String? {
        delay(400)
        Log.i(ETIQUETA, "--- " + cupom.titulo + " ---")
        if (cupom.marca != null) {
            Log.i(ETIQUETA, "[logomarca " + cupom.marca.largura + "x" + cupom.marca.altura + "]")
        }
        for (linha in cupom.linhas) {
            Log.i(ETIQUETA, (if (linha.letraDupla) "## " else "   ") + linha.texto)
        }
        Log.i(ETIQUETA, "--- corta ---")
        return null
    }

    private companion object {
        const val ETIQUETA = "Impressora"
    }
}
