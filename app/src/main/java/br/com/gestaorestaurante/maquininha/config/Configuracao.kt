package br.com.gestaorestaurante.maquininha.config

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * O que este aparelho precisa saber para existir: o endereço do servidor e o
 * token da unidade.
 *
 * O token é a credencial e o endereço ao mesmo tempo — é ele que diz de qual
 * restaurante esta maquininha é, do mesmo jeito que no agente de impressão.
 * Fica no DataStore do aplicativo, que no Android é privado ao pacote; quem
 * conseguir tirar o token daqui já é dono do aparelho.
 */
private val Context.armazem by preferencesDataStore(name = "configuracao")

private val CHAVE_SERVIDOR = stringPreferencesKey("servidor")
private val CHAVE_TOKEN = stringPreferencesKey("token")
private val CHAVE_UNIDADE = stringPreferencesKey("unidade")
private val CHAVE_OPERADOR = stringPreferencesKey("operador")
private val CHAVE_NOME_DO_OPERADOR = stringPreferencesKey("operadorNome")
private val CHAVE_AGENTE = stringPreferencesKey("agente")

data class Configuracao(
    val servidor: String = "",
    val token: String = "",
    /** Nome da unidade, como o servidor respondeu da última vez. Só para a tela. */
    val unidade: String = "",
    /**
     * O turno de quem digitou o PIN. Vale doze horas e mora aqui para o
     * operador não redigitar a cada venda; o PIN em si nunca é guardado.
     */
    val operador: String = "",
    val operadorNome: String = "",
    /**
     * O apelido deste aparelho na fila de impressão.
     *
     * Sorteado na primeira vez e guardado: a fila precisa distinguir dois
     * aparelhos da mesma casa, e o token não serve para isso — ele é da
     * unidade, e todos os aparelhos dela usam o mesmo.
     */
    val agente: String = "",
) {
    val completa: Boolean get() = servidor.isNotBlank() && token.isNotBlank()
    val comOperador: Boolean get() = operador.isNotBlank()
}

class RepositorioDeConfiguracao(private val contexto: Context) {

    val fluxo: Flow<Configuracao> = contexto.armazem.data.map { p ->
        Configuracao(
            servidor = p[CHAVE_SERVIDOR].orEmpty(),
            token = p[CHAVE_TOKEN].orEmpty(),
            unidade = p[CHAVE_UNIDADE].orEmpty(),
            operador = p[CHAVE_OPERADOR].orEmpty(),
            operadorNome = p[CHAVE_NOME_DO_OPERADOR].orEmpty(),
            agente = p[CHAVE_AGENTE].orEmpty(),
        )
    }

    suspend fun atual(): Configuracao = fluxo.first()

    /**
     * O apelido deste aparelho na fila, sorteado uma vez e guardado.
     *
     * Sorteado, e não derivado do aparelho: um identificador de hardware
     * seria um dado a mais viajando a cada busca da fila, sem nenhuma
     * pergunta que só ele responda. Quatro letras bastam para separar duas
     * maquininhas da mesma casa na tela de impressão.
     */
    suspend fun agente(): String {
        val guardado = atual().agente
        if (guardado.isNotBlank()) return guardado

        val sorteado = "maquininha-" + (1..4).map { LETRAS.random() }.joinToString("")
        contexto.armazem.edit { p -> p[CHAVE_AGENTE] = sorteado }
        return sorteado
    }

    suspend fun guardar(servidor: String, token: String) {
        contexto.armazem.edit { p ->
            // Barra no fim faria toda URL montada aqui virar `//api/...`, que
            // alguns proxies tratam como rota diferente.
            p[CHAVE_SERVIDOR] = servidor.trim().trimEnd('/')
            p[CHAVE_TOKEN] = token.trim()
        }
    }

    suspend fun guardarOperador(token: String, nome: String) {
        contexto.armazem.edit { p ->
            p[CHAVE_OPERADOR] = token
            p[CHAVE_NOME_DO_OPERADOR] = nome
        }
    }

    /**
     * Fim de turno, ou token recusado pelo servidor: volta a pedir o PIN.
     *
     * O `motivo` vai para o logcat, e não é enfeite: um turno que cai sozinho
     * no meio do salão é a queixa mais difícil de investigar deste aplicativo
     * — sem rastro, resta adivinhar. Duas palavras aqui poupam uma tarde.
     */
    suspend fun esquecerOperador(motivo: String) {
        Log.i(ETIQUETA, "turno encerrado: $motivo")
        contexto.armazem.edit { p ->
            p.remove(CHAVE_OPERADOR)
            p.remove(CHAVE_NOME_DO_OPERADOR)
        }
    }

    suspend fun guardarUnidade(nome: String) {
        contexto.armazem.edit { p -> p[CHAVE_UNIDADE] = nome }
    }

    private companion object {
        const val ETIQUETA = "Maquininha"
        // Sem vogais nem dígitos parecidos: o apelido é lido em voz alta por
        // quem procura de qual aparelho saiu o papel.
        val LETRAS = ('a'..'z').filter { it !in "aeiou" } + ('2'..'9').toList()
    }
}
