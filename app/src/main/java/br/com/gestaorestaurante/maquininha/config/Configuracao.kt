package br.com.gestaorestaurante.maquininha.config

import android.content.Context
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

data class Configuracao(
    val servidor: String = "",
    val token: String = "",
    /** Nome da unidade, como o servidor respondeu da última vez. Só para a tela. */
    val unidade: String = "",
) {
    val completa: Boolean get() = servidor.isNotBlank() && token.isNotBlank()
}

class RepositorioDeConfiguracao(private val contexto: Context) {

    val fluxo: Flow<Configuracao> = contexto.armazem.data.map { p ->
        Configuracao(
            servidor = p[CHAVE_SERVIDOR].orEmpty(),
            token = p[CHAVE_TOKEN].orEmpty(),
            unidade = p[CHAVE_UNIDADE].orEmpty(),
        )
    }

    suspend fun atual(): Configuracao = fluxo.first()

    suspend fun guardar(servidor: String, token: String) {
        contexto.armazem.edit { p ->
            // Barra no fim faria toda URL montada aqui virar `//api/...`, que
            // alguns proxies tratam como rota diferente.
            p[CHAVE_SERVIDOR] = servidor.trim().trimEnd('/')
            p[CHAVE_TOKEN] = token.trim()
        }
    }

    suspend fun guardarUnidade(nome: String) {
        contexto.armazem.edit { p -> p[CHAVE_UNIDADE] = nome }
    }
}
