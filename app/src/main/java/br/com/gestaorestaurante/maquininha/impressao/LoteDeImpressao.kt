package br.com.gestaorestaurante.maquininha.impressao

import br.com.gestaorestaurante.maquininha.api.Api
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
         * O servidor recusou a baixa: o papel já era de outro aparelho.
         *
         * A fila entrega cada trabalho a um só, e a baixa é de quem levou. Se
         * a reserva vencer no meio de uma impressão demorada, outro aparelho
         * leva o trabalho e imprime também — de propósito, papel que não saiu
         * merece outra chance —, e a confirmação deste chega depois da dele.
         *
         * Não é falha de rede e não é papel que não saiu: insistir não muda a
         * resposta, e parar o lote por causa dela atrasaria as comandas que
         * vêm atrás. Conta aqui para aparecer na notificação de quem está com
         * o aparelho na mão, e o lote segue.
         */
        val recusadas: Int = 0,
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
        var recusadas = 0

        for (trabalho in trabalhos) {
            val falha = tentar(trabalho)

            /*
             * A confirmação é o que tira o trabalho da fila do servidor. Ela
             * insiste algumas vezes porque um soluço de rede aqui custa papel
             * repetido na cozinha — e se nem assim for, o lote para.
             *
             * Recusa é outra coisa: o servidor respondeu, e respondeu que
             * esta baixa não é deste aparelho. Insistir não muda, e parar
             * seria deixar a cozinha sem as comandas de trás.
             */
            when (confirmarTeimoso(trabalho.id, ok = falha == null, erro = falha)) {
                Confirmacao.FEITA -> if (falha == null) impressos++ else comErro++
                Confirmacao.RECUSADA -> recusadas++
                Confirmacao.SEM_CONTATO ->
                    return Resultado(impressos, comErro, recusadas, interrompido = true)
            }
        }

        return Resultado(impressos, comErro, recusadas)
    }

    /** O que o servidor fez com a baixa. */
    private enum class Confirmacao { FEITA, RECUSADA, SEM_CONTATO }

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

    /** Insiste no soluço de rede; para na primeira recusa, que não muda. */
    private suspend fun confirmarTeimoso(id: String, ok: Boolean, erro: String?): Confirmacao {
        repeat(TENTATIVAS) { tentativa ->
            try {
                confirmar(id, ok, erro)
                return Confirmacao.FEITA
            } catch (e: CancellationException) {
                throw e
            } catch (e: Api.RegraDaCasa) {
                // 409: a baixa é de outro aparelho, ou a fila mudou de estado.
                return Confirmacao.RECUSADA
            } catch (e: Api.RecusadoPeloServidor) {
                // 400 e 404: trabalho que não existe mais, corpo que não serve.
                return Confirmacao.RECUSADA
            } catch (e: Exception) {
                if (tentativa < TENTATIVAS - 1) delay(300L shl tentativa)
            }
        }
        return Confirmacao.SEM_CONTATO
    }

    private companion object {
        const val TENTATIVAS = 3
    }
}
