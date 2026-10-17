package br.com.gestaorestaurante.maquininha.impressao

import br.com.gestaorestaurante.maquininha.api.TrabalhoDeImpressao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Um lote da fila de impressão, trabalho a trabalho.
 *
 * Mora fora do serviço porque é a parte que decide o que a cozinha recebe — e
 * a única que dá para conferir na JVM, sem maquininha e sem bobina. O serviço
 * cuida de ficar de pé, buscar o lote e avisar na notificação.
 *
 * O que esta classe conserta: o laço tratava o lote inteiro num `try` só.
 *
 * - Um trabalho que estourasse ao virar papel — conteúdo estranho, bobina que
 *   recusa, SDK que lança — nunca recebia `ok=false`. Ficava na cabeça da fila
 *   do servidor e voltava no lote seguinte, e no seguinte: a cozinha parava de
 *   receber **tudo** por causa de um papel só, a cada três segundos, para
 *   sempre.
 * - E o que já tinha saído no papel antes dele ficava sem confirmação, então o
 *   servidor reentregava: a mesma comanda saindo de novo na próxima volta.
 *
 * Aqui cada trabalho é uma história sua. Quem não vira papel sai da fila como
 * erro — o servidor marca e a gestão vê —, e quem vem atrás imprime.
 */
class LoteDeImpressao(
    private val montar: suspend (TrabalhoDeImpressao) -> Cupom,
    /** Devolve `null` quando saiu, ou o motivo da falha. */
    private val imprimir: suspend (Cupom) -> String?,
    private val confirmar: suspend (id: String, ok: Boolean, erro: String?) -> Unit,
) {

    data class Resultado(
        val impressos: Int = 0,
        val comErro: Int = 0,
        /**
         * A rede caiu no meio: o que sobrou do lote nem foi tentado.
         *
         * De propósito. Sem conseguir confirmar, o servidor vai reentregar o
         * que já saiu; continuar imprimindo o resto só multiplicaria o papel
         * repetido quando ele reentregar o lote inteiro.
         */
        val interrompido: Boolean = false,
    )

    suspend fun rodar(trabalhos: List<TrabalhoDeImpressao>): Resultado {
        var impressos = 0
        var comErro = 0

        for (trabalho in trabalhos) {
            val falha = tentar(trabalho)

            /*
             * A confirmação é o que tira o trabalho da fila do servidor. Ela
             * insiste algumas vezes porque um soluço de rede aqui custa papel
             * repetido na cozinha — e se nem assim for, o lote para.
             */
            if (!confirmarTeimoso(trabalho.id, ok = falha == null, erro = falha)) {
                return Resultado(impressos, comErro, interrompido = true)
            }

            if (falha == null) impressos++ else comErro++
        }

        return Resultado(impressos, comErro)
    }

    /** O papel deste trabalho, ou o motivo de ele não ter saído. */
    private suspend fun tentar(trabalho: TrabalhoDeImpressao): String? {
        val cupom = try {
            montar(trabalho)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Conteúdo que não vira papel não melhora na próxima volta: vale
            // mais sair da fila com o motivo do que travar quem está atrás.
            return "não deu para montar o papel: " + (e.message ?: e::class.simpleName)
        }

        return try {
            imprimir(cupom)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: "falha na impressora"
        }
    }

    /** `false` quando nem insistindo deu para falar com o servidor. */
    private suspend fun confirmarTeimoso(id: String, ok: Boolean, erro: String?): Boolean {
        repeat(TENTATIVAS) { tentativa ->
            try {
                confirmar(id, ok, erro)
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (tentativa < TENTATIVAS - 1) delay(300L shl tentativa)
            }
        }
        return false
    }

    private companion object {
        const val TENTATIVAS = 3
    }
}
