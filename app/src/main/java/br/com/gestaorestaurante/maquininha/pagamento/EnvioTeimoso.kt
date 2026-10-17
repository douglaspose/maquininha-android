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
 * Onde a fila mora.
 *
 * Existe como interface para a regra acima — a ordem de gravar e mandar, o que
 * acontece no cancelamento — poder ser testada na JVM, sem emulador. É o
 * caminho do dinheiro: vale o custo de uma interface de dois métodos.
 */
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
)

/**
 * O crachá daquele pagamento não vale mais — turno vencido ou aparelho sem
 * turno nenhum.
 *
 * Tem nome próprio porque o escoamento faz coisas opostas nos dois casos: na
 * queda de rede ele para (o resto também não vai passar), e aqui ele pula
 * este e segue com os outros. Quem traduz a recusa do servidor para cá é
 * quem chama, para esta fila não depender da `Api`.
 */
class TurnoVencido(mensagem: String) : Exception(mensagem)

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
    suspend fun enviar(pagamento: PagamentoParaEnviar, cracha: String): RespostaDoPagamento? {
        val pendente = PendenteDeEnvio(pagamento, cracha)
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
        if (deposito.ler().isEmpty()) return 0

        var enviados = 0
        val adiados = mutableSetOf<String>()
        // Um a um, e relendo a fila a cada volta: o escoamento é lento por
        // natureza, e uma venda nova pode entrar no meio dele.
        while (true) {
            val proximo = tranca.withLock {
                deposito.ler().firstOrNull { it.pagamento.referencia !in adiados }
            } ?: break
            try {
                mandar(proximo.pagamento, proximo.cracha.ifBlank { null })
            } catch (e: CancellationException) {
                throw e
            } catch (e: TurnoVencido) {
                /*
                 * O crachá de quem cobrou venceu — doze horas depois, numa
                 * casa que passou a noite sem rede. Não é a rede, então os
                 * outros continuam saindo; este espera a pessoa abrir turno de
                 * novo, porque a venda é dela e entrar no nome de outra pessoa
                 * é pior do que esperar.
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

    suspend fun quantasPendentes(): Int = deposito.ler().size

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
