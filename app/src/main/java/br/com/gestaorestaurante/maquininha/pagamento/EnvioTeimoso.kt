package br.com.gestaorestaurante.maquininha.pagamento

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import br.com.gestaorestaurante.maquininha.api.Api
import br.com.gestaorestaurante.maquininha.api.ResultadoDaCobrancaEnviado
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
 * Então: tenta, e se a rede estiver fora, guarda em disco e tenta de novo na
 * próxima oportunidade. A fila sobrevive a queda de energia e a aplicativo
 * fechado, que é exatamente quando isso acontece.
 */
private val Context.pendencias by preferencesDataStore(name = "resultados-pendentes")

private val CHAVE_FILA = stringPreferencesKey("fila")

class EnvioTeimoso(private val contexto: Context, private val api: Api) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializador = ListSerializer(ResultadoDaCobrancaEnviado.serializer())

    /**
     * Tenta mandar agora, com três tentativas rápidas. O que não for não se
     * perde: fica na fila em disco. Volta `true` se o servidor recebeu.
     */
    suspend fun enviar(resultado: ResultadoDaCobrancaEnviado): Boolean {
        repeat(3) { tentativa ->
            try {
                api.enviarResultado(resultado)
                return true
            } catch (e: Exception) {
                // 1s, 2s, 4s: o suficiente para atravessar uma oscilação de
                // wi-fi sem deixar o caixa parado esperando.
                delay(1000L shl tentativa)
            }
        }
        guardar(resultado)
        return false
    }

    /** Roda na abertura do aplicativo e a cada volta da rede. */
    suspend fun escoar() {
        val fila = ler()
        if (fila.isEmpty()) return
        val sobraram = fila.filter { resultado ->
            try {
                api.enviarResultado(resultado)
                false
            } catch (e: Exception) {
                true
            }
        }
        gravar(sobraram)
    }

    suspend fun quantasPendentes(): Int = ler().size

    private suspend fun guardar(resultado: ResultadoDaCobrancaEnviado) {
        gravar(ler() + resultado)
    }

    private suspend fun ler(): List<ResultadoDaCobrancaEnviado> {
        val texto = contexto.pendencias.data.first()[CHAVE_FILA] ?: return emptyList()
        return try {
            json.decodeFromString(serializador, texto)
        } catch (e: Exception) {
            // Fila corrompida não pode impedir o aplicativo de abrir.
            emptyList()
        }
    }

    private suspend fun gravar(fila: List<ResultadoDaCobrancaEnviado>) {
        contexto.pendencias.edit { it[CHAVE_FILA] = json.encodeToString(serializador, fila) }
    }
}
