package br.com.gestaorestaurante.maquininha.pagamento

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import br.com.gestaorestaurante.maquininha.api.PagamentoParaEnviar
import br.com.gestaorestaurante.maquininha.api.RespostaDoPagamento
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * O resultado de uma cobrança não pode se perder.
 *
 * O dinheiro já saiu do cartão do cliente quando a credenciadora aprova. Se o
 * servidor não ficar sabendo, a conta continua aberta no salão e alguém vai
 * cobrar de novo — e a segunda cobrança é um estorno, uma discussão no balcão
 * e um cliente a menos.
 *
 * **Grava primeiro, manda depois.** A ordem inversa — tentar a rede e só
 * guardar quem falhou — parece igual e não é: a tentativa demora dezenas de
 * segundos justamente quando a rede está ruim, e nesse intervalo basta o
 * aplicativo ser fechado para o pagamento aprovado sumir sem rastro. Guardado
 * antes, o pior caso é ele sair duas vezes para o servidor, que reconhece a
 * referência e não duplica.
 */
/*
 * O mesmo tratamento da configuração: corrompido recomeça vazio.
 *
 * Aqui dói mais — o que se perde é pagamento aprovado que o servidor ainda não
 * recebeu. Mas o aplicativo que não abre não manda esse pagamento nunca, e
 * ainda leva junto o salão inteiro: sem a tela não há como cobrar, lançar nem
 * fechar conta. A leitura já tolera JSON estragado (ver `DepositoEmDisco`);
 * isto cobre o arquivo estragado por baixo dela.
 */
private val Context.pendencias by preferencesDataStore(
    name = "resultados-pendentes",
    corruptionHandler = ReplaceFileCorruptionHandler {
        Log.w("Maquininha", "fila de pagamentos corrompida no disco; recomeçando vazia", it)
        emptyPreferences()
    },
)

private val CHAVE_FILA = stringPreferencesKey("fila")

/**
 * Um pagamento esperando a rede, com o crachá de quem cobrou.
 *
 * O crachá vai junto porque a venda entra no servidor em nome de quem o
 * apresenta: escoada com o crachá de quem estiver no aparelho quando a rede
 * voltar, ela entraria no nome da pessoa errada — e é por esse nome que a casa
 * confere o caixa no fim do turno.
 */
@Serializable
data class PendenteDeEnvio(
    val pagamento: PagamentoParaEnviar,
    /** Vazio nos pagamentos guardados antes desta versão. */
    val cracha: String = "",
    /**
     * Por que o servidor recusou esta venda de vez.
     *
     * Nulo é o normal: esperando a rede. Preenchido, o aparelho parou de
     * tentar — o corpo desta venda nunca vai ser aceito como está — e ela fica
     * guardada, contada à parte na tela, para a casa resolver com o
     * comprovante na mão. Jogá-la fora seria sumir com dinheiro que saiu do
     * cartão do cliente; tentar de novo seria segurar a fila inteira atrás
     * dela a noite toda.
     */
    val recusa: String? = null,
    /**
     * Quando e por quem foi cobrada.
     *
     * Só servem à tela das recusadas: para a casa achar a venda no extrato da
     * adquirente, o que ela procura é a hora e o valor, e o nome diz a quem
     * perguntar o que aconteceu. Zerados nos pendentes guardados antes desta
     * versão — a lista mostra o que tem.
     */
    val cobradaEm: Long = 0,
    val operadorNome: String = "",
)

/**
 * Recusa que pode mudar de ideia: quem está errado é o momento, não o corpo.
 *
 * Tem nome próprio porque o escoamento faz coisas opostas nos dois casos: na
 * queda de rede ele para (o resto também não vai passar), e aqui ele pula
 * este e segue com os outros. Quem traduz a recusa do servidor para cá é
 * quem chama, para esta fila não depender da `Api`.
 */
open class EsperaACasa(mensagem: String) : Exception(mensagem)

/** O crachá daquele pagamento não vale mais — turno vencido, ou nenhum. */
class TurnoVencido(mensagem: String) : EsperaACasa(mensagem)

/**
 * O servidor recusou de vez: este corpo não vai ser aceito como está.
 *
 * Valor acima do que a coluna guarda, referência comprida demais, conta que
 * não existe mais. Insistir nisso foi o que travou a fila a noite inteira: a
 * venda recusada ficava na cabeça e as de trás não saíam. Agora ela sai da
 * fila marcada, e a tela conta quantas são.
 */
class RecusaDefinitiva(mensagem: String) : Exception(mensagem)

/**
 * O que aconteceu com o reenvio de uma venda recusada.
 *
 * A tela precisa dizer três coisas diferentes, e cada uma manda a pessoa fazer
 * outra coisa: parabéns e pronto; a mesma recusa (ou outra) de novo, e aí o
 * motivo mudou e vale ler; ou ficou na fila, e some sozinha quando a rede
 * voltar — sem ninguém apertar nada.
 */
sealed interface ResultadoDoReenvio {
    /** O servidor recebeu: a venda saiu do aparelho. */
    object Enviada : ResultadoDoReenvio

    /** Recusada outra vez. O motivo é o de agora, não o de antes. */
    data class Recusada(val motivo: String) : ResultadoDoReenvio

    /**
     * Voltou para a fila: rede fora, turno vencido ou caixa fechado.
     *
     * Não é fracasso — é o estado normal de quem espera. O escoamento a manda
     * quando der, e ela sai da lista das recusadas de qualquer jeito.
     */
    object NaFila : ResultadoDoReenvio

    /** Não há venda recusada com essa referência (já saiu, ou nunca houve). */
    object NaoEncontrada : ResultadoDoReenvio
}

interface DepositoDePendentes {
    suspend fun ler(): List<PendenteDeEnvio>
    suspend fun gravar(fila: List<PendenteDeEnvio>)
}

class DepositoEmDisco(private val contexto: Context) : DepositoDePendentes {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializador = ListSerializer(PendenteDeEnvio.serializer())
    private val serializadorAntigo = ListSerializer(PagamentoParaEnviar.serializer())

    override suspend fun ler(): List<PendenteDeEnvio> {
        val texto = contexto.pendencias.data.first()[CHAVE_FILA] ?: return emptyList()
        return try {
            json.decodeFromString(serializador, texto)
        } catch (e: Exception) {
            /*
             * Formato antigo: lista de pagamentos, sem crachá. Atualizar o
             * aplicativo com a fila cheia não pode jogar fora dinheiro já
             * cobrado — eles entram sem crachá e saem com o turno de agora,
             * que é o que acontecia antes desta versão de qualquer jeito.
             */
            try {
                json.decodeFromString(serializadorAntigo, texto).map { PendenteDeEnvio(it) }
            } catch (e2: Exception) {
                // Fila corrompida não pode impedir o aplicativo de abrir.
                emptyList()
            }
        }
    }

    override suspend fun gravar(fila: List<PendenteDeEnvio>) {
        contexto.pendencias.edit { it[CHAVE_FILA] = json.encodeToString(serializador, fila) }
    }
}

class EnvioTeimoso(
    private val deposito: DepositoDePendentes,
    /**
     * Como se manda um pagamento. Uma função, e não a `Api` inteira, para o
     * teste poder responder "a rede caiu" sem levantar um servidor.
     */
    private val mandar: suspend (PagamentoParaEnviar, String?) -> RespostaDoPagamento,
) {
    /**
     * A fila é lida, mudada e regravada; sem trava, o escoamento que roda a
     * cada volta da rede apaga a venda que acabou de entrar.
     */
    private val tranca = Mutex()

    /**
     * Guarda o pagamento e tenta mandar na hora.
     *
     * Devolve a resposta do servidor quando ele recebeu, e nulo quando o
     * pagamento ficou na fila — aí a conta segue aberta no salão até o
     * escoamento conseguir.
     */
    suspend fun enviar(
        pagamento: PagamentoParaEnviar,
        cracha: String,
        /** Para a tela das recusadas saber a quem perguntar. */
        operadorNome: String = "",
        agoraEmMillis: Long = System.currentTimeMillis(),
    ): RespostaDoPagamento? {
        val pendente = PendenteDeEnvio(
            pagamento = pagamento,
            cracha = cracha,
            cobradaEm = agoraEmMillis,
            operadorNome = operadorNome,
        )
        guardar(pendente)
        return tentar(pendente)
    }

    /**
     * Três tentativas rápidas, com 1s e 2s entre elas: o suficiente para
     * atravessar uma oscilação de wi-fi sem deixar o caixa parado esperando.
     *
     * O cancelamento é relançado em vez de virar "falhou": quem cancela é o
     * aplicativo sendo fechado, e nesse caso não há o que tentar de novo — o
     * pagamento já está em disco e sai no próximo escoamento.
     */
    private suspend fun tentar(pendente: PendenteDeEnvio): RespostaDoPagamento? {
        repeat(TENTATIVAS) { tentativa ->
            try {
                val resposta = mandar(pendente.pagamento, pendente.cracha.ifBlank { null })
                remover(pendente)
                return resposta
            } catch (e: CancellationException) {
                throw e
            } catch (e: RecusaDefinitiva) {
                // Recusa que não muda: insistir só gasta o tempo do caixa, que
                // está com o cliente na frente.
                marcarRecusada(pendente, e.message ?: "O servidor recusou esta venda.")
                return null
            } catch (e: EsperaACasa) {
                // Nem a rede nem o corpo: o momento. Fica na fila e tenta no
                // próximo escoamento, sem gastar as três tentativas agora.
                return null
            } catch (e: Exception) {
                if (tentativa < TENTATIVAS - 1) delay(1000L shl tentativa)
            }
        }
        return null
    }

    /**
     * Manda o que ficou para trás.
     *
     * Roda na abertura do aplicativo e a cada vez que o salão responde — que é
     * a prova de que a rede voltou. Item que falha de novo continua na fila:
     * uma noite inteira de aparelho aberto não pode deixar pagamento parado
     * esperando alguém reiniciar o aplicativo.
     *
     * Devolve quantos saíram, para a tela parar de prometer o que já aconteceu.
     */
    suspend fun escoar(): Int {
        if (deposito.ler().none { it.recusa == null }) return 0

        var enviados = 0
        val adiados = mutableSetOf<String>()
        // Um a um, e relendo a fila a cada volta: o escoamento é lento por
        // natureza, e uma venda nova pode entrar no meio dele.
        while (true) {
            val proximo = tranca.withLock {
                deposito.ler().firstOrNull {
                    // Recusada de vez não volta para a fila de tentativas: ela
                    // está aqui só para não sumir, e a tela conta quantas são.
                    it.recusa == null && it.pagamento.referencia !in adiados
                }
            } ?: break
            try {
                mandar(proximo.pagamento, proximo.cracha.ifBlank { null })
            } catch (e: CancellationException) {
                throw e
            } catch (e: RecusaDefinitiva) {
                /*
                 * O servidor não vai mudar de ideia sobre este corpo. Ela sai
                 * da fila de tentativas marcada — guardada, contada na tela —
                 * e o escoamento segue: era isto que travava a noite inteira,
                 * com a venda recusada na cabeça e as boas atrás dela.
                 */
                marcarRecusada(proximo, e.message ?: "O servidor recusou esta venda.")
                adiados += proximo.pagamento.referencia
            } catch (e: EsperaACasa) {
                /*
                 * O crachá venceu, ou o caixa ainda não abriu. Não é a rede,
                 * então os outros continuam saindo; este espera a casa — a
                 * venda é de quem cobrou, e entrar no nome de outra pessoa (ou
                 * em caixa nenhum) é pior do que esperar.
                 */
                adiados += proximo.pagamento.referencia
            } catch (e: Exception) {
                // A rede caiu de novo: o resto fica para a próxima.
                break
            }
            if (proximo.pagamento.referencia !in adiados) {
                remover(proximo)
                enviados++
            }
        }
        return enviados
    }

    /** Quantas ainda esperam a rede ou a casa. */
    suspend fun quantasPendentes(): Int = deposito.ler().count { it.recusa == null }

    /** Quantas o servidor recusou de vez, e estão guardadas esperando a casa resolver. */
    suspend fun quantasRecusadas(): Int = deposito.ler().count { it.recusa != null }

    /** As recusadas, da mais nova para a mais velha, como a tela as mostra. */
    suspend fun recusadas(): List<PendenteDeEnvio> =
        deposito.ler().filter { it.recusa != null }.sortedByDescending { it.cobradaEm }

    /**
     * A casa consertou a causa: manda esta venda de novo.
     *
     * Recusa definitiva é o servidor dizendo "este corpo não serve **como
     * está**" — e às vezes o que estava errado era do lado de fora: o aparelho
     * configurado com o token da unidade errada, uma forma de pagamento que
     * ninguém havia cadastrado, um defeito nosso que foi corrigido e
     * publicado. Consertado o motivo, a venda passa a ser aceitável, e até
     * agora o aparelho só sabia **esquecê-la** ("dar baixa"), obrigando a
     * registrar à mão na retaguarda uma venda que o servidor aceitaria.
     *
     * Reenviar é seguro: o servidor reconhece a `referenciaExterna` e não
     * registra duas vezes a mesma cobrança. O pior caso é a recusa voltar, com
     * o motivo de agora.
     *
     * A venda volta para a fila antes da tentativa — e **continua lá** se a
     * rede estiver fora ou a casa não estiver pronta. Assim ela escoa sozinha
     * depois, sem ninguém apertar de novo.
     */
    suspend fun reenviar(referencia: String): ResultadoDoReenvio {
        val alvo = tranca.withLock {
            deposito.ler().firstOrNull { it.pagamento.referencia == referencia && it.recusa != null }
        } ?: return ResultadoDoReenvio.NaoEncontrada

        // De volta à fila de tentativas: sem isto o escoamento a ignoraria,
        // porque é a marca de recusa que o faz pular esta venda.
        limparRecusa(referencia)
        val pendente = alvo.copy(recusa = null)

        return try {
            mandar(pendente.pagamento, pendente.cracha.ifBlank { null })
            remover(pendente)
            ResultadoDoReenvio.Enviada
        } catch (e: CancellationException) {
            throw e
        } catch (e: RecusaDefinitiva) {
            val motivo = e.message ?: "O servidor recusou esta venda."
            marcarRecusada(pendente, motivo)
            ResultadoDoReenvio.Recusada(motivo)
        } catch (e: EsperaACasa) {
            ResultadoDoReenvio.NaFila
        } catch (e: Exception) {
            ResultadoDoReenvio.NaFila
        }
    }

    /**
     * A casa resolveu esta na retaguarda: pode sair do aparelho.
     *
     * Só sai o que está recusado. Pendente não some por engano daqui — ele
     * ainda vai ser enviado, e apagá-lo seria jogar fora dinheiro que o
     * servidor nunca soube que entrou.
     */
    suspend fun darBaixa(referencia: String) {
        withContext(NonCancellable) {
            tranca.withLock {
                deposito.gravar(
                    deposito.ler().filterNot {
                        it.pagamento.referencia == referencia && it.recusa != null
                    }
                )
            }
        }
    }

    /**
     * Tira a marca de recusa, devolvendo a venda à fila de tentativas.
     *
     * Só a marca: a venda não se move e nada do conteúdo muda. O motivo antigo
     * sai junto porque ele deixou de ser verdade no instante em que alguém
     * decidiu tentar de novo — e, se a recusa voltar, quem escreve o motivo
     * novo é a resposta nova.
     */
    private suspend fun limparRecusa(referencia: String) {
        withContext(NonCancellable) {
            tranca.withLock {
                deposito.gravar(
                    deposito.ler().map {
                        if (it.pagamento.referencia == referencia) it.copy(recusa = null) else it
                    }
                )
            }
        }
    }

    /**
     * Marca a venda como recusada sem tirá-la do disco.
     *
     * Fora da fila de tentativas, mas dentro do aparelho: o cartão do cliente
     * foi debitado, e o que a casa precisa é saber que existe uma venda sem
     * registro — com o NSU no comprovante — para resolver na retaguarda.
     */
    private suspend fun marcarRecusada(pendente: PendenteDeEnvio, motivo: String) {
        withContext(NonCancellable) {
            tranca.withLock {
                deposito.gravar(
                    deposito.ler().map {
                        if (it.pagamento.referencia == pendente.pagamento.referencia) {
                            it.copy(recusa = motivo)
                        } else {
                            it
                        }
                    }
                )
            }
        }
    }

    /**
     * Gravar e remover são indiferentes a cancelamento de propósito: é a única
     * parte do caminho que não pode ficar pela metade.
     */
    private suspend fun guardar(pendente: PendenteDeEnvio) {
        withContext(NonCancellable) {
            tranca.withLock {
                val fila = deposito.ler()
                // Reenvio do mesmo pagamento não vira duas linhas na fila.
                if (fila.none { it.pagamento.referencia == pendente.pagamento.referencia }) {
                    deposito.gravar(fila + pendente)
                }
            }
        }
    }

    private suspend fun remover(pendente: PendenteDeEnvio) {
        withContext(NonCancellable) {
            tranca.withLock {
                deposito.gravar(
                    deposito.ler().filter {
                        it.pagamento.referencia != pendente.pagamento.referencia
                    }
                )
            }
        }
    }

    private companion object {
        const val TENTATIVAS = 3
    }
}
