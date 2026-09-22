package br.com.gestaorestaurante.maquininha.api

import br.com.gestaorestaurante.maquininha.config.RepositorioDeConfiguracao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * A conversa com o servidor do restaurante.
 *
 * Sempre pelo token da unidade no header, nunca por sessão de usuário: uma
 * maquininha é um serviço, não uma pessoa. O mesmo modelo do agente de
 * impressão, e de propósito — quem já sabe operar um sabe operar o outro.
 */
class Api(private val configuracao: RepositorioDeConfiguracao) {

    /*
     * Timeouts curtos e explícitos: esta conversa acontece no meio de uma
     * venda, com um cliente esperando de pé. Melhor falhar em dez segundos e
     * deixar o caixa decidir do que pendurar a tela até o padrão do OkHttp.
     */
    private val cliente = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val tipoJson = "application/json; charset=utf-8".toMediaType()

    /** Falha da conversa com o servidor, já com um texto que cabe numa tela. */
    class FalhaNaApi(mensagem: String, causa: Throwable? = null) : IOException(mensagem, causa)

    private suspend fun <T> chamar(
        caminho: String,
        corpo: String? = null,
        leitura: (String) -> T,
    ): T = withContext(Dispatchers.IO) {
        val config = configuracao.atual()
        if (!config.completa) throw FalhaNaApi("Aparelho sem servidor ou token configurado.")

        val requisicao = Request.Builder()
            .url(config.servidor + caminho)
            .header("Authorization", "Bearer ${config.token}")
            .apply { if (corpo != null) post(corpo.toRequestBody(tipoJson)) else get() }
            .build()

        try {
            cliente.newCall(requisicao).execute().use { resposta ->
                val texto = resposta.body?.string().orEmpty()
                if (!resposta.isSuccessful) {
                    // 401 tem nome próprio: é o erro que o caixa consegue
                    // resolver sozinho, reconfigurando o token.
                    if (resposta.code == 401) throw FalhaNaApi("Token recusado pelo servidor.")
                    throw FalhaNaApi("Servidor respondeu ${resposta.code}.")
                }
                leitura(texto)
            }
        } catch (e: FalhaNaApi) {
            throw e
        } catch (e: IOException) {
            throw FalhaNaApi("Sem resposta do servidor.", e)
        }
    }

    /** Até 20 trabalhos pendentes, do mais antigo para o mais novo. */
    suspend fun buscarFilaDeImpressao(): RespostaDaFila =
        chamar("/api/impressao") { json.decodeFromString(RespostaDaFila.serializer(), it) }
            .also { if (it.unidade.isNotBlank()) configuracao.guardarUnidade(it.unidade) }

    /** Confirma o que saiu — ou relata por que não saiu. */
    suspend fun confirmarImpressao(id: String, ok: Boolean, erro: String? = null) {
        val corpo = json.encodeToString(
            ConfirmacaoDeImpressao.serializer(),
            ConfirmacaoDeImpressao(id = id, ok = ok, erro = erro),
        )
        chamar("/api/impressao", corpo) { }
    }

    /**
     * As cobranças que o caixa mandou para esta maquininha.
     *
     * Esta rota ainda não existe no servidor: enquanto não existir, a tela de
     * cobrança funciona pelo valor digitado na própria maquininha.
     */
    suspend fun buscarCobrancas(): RespostaDeCobrancas =
        chamar("/api/maquininha/cobrancas") {
            json.decodeFromString(RespostaDeCobrancas.serializer(), it)
        }

    /**
     * Conta ao servidor o que aconteceu com a cobrança.
     *
     * É a chamada que não pode se perder: uma transação aprovada que o
     * servidor não soube vira cobrança dobrada no cliente. Quem chama tem que
     * insistir até conseguir — veja `EnvioTeimoso`.
     */
    suspend fun enviarResultado(resultado: ResultadoDaCobrancaEnviado) {
        val corpo = json.encodeToString(
            ResultadoDaCobrancaEnviado.serializer(),
            resultado,
        )
        chamar("/api/maquininha/cobrancas", corpo) { }
    }
}
