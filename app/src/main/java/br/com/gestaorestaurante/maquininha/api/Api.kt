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

    /**
     * A chamada precisava de um turno aberto e não tinha.
     *
     * [recusadoPeloServidor] separa duas coisas que pareciam uma só:
     *
     *  - **true** — o servidor devolveu 401 numa chamada com operador. O turno
     *    morreu de verdade (venceu, ou o aparelho mudou de unidade), e guardar
     *    o token não serve mais para nada;
     *  - **false** — a própria maquininha viu que não tinha turno e nem chegou
     *    a sair pela rede. Aqui ninguém recusou nada, e apagar o turno por
     *    causa disso é jogar fora um token que pode estar bom.
     */
    class PrecisaDoPin(
        mensagem: String,
        val recusadoPeloServidor: Boolean,
    ) : IOException(mensagem)

    private suspend fun <T> chamar(
        caminho: String,
        corpo: String? = null,
        comOperador: Boolean = false,
        leitura: (String) -> T,
    ): T = withContext(Dispatchers.IO) {
        val config = configuracao.atual()
        if (!config.completa) throw FalhaNaApi("Aparelho sem servidor ou token configurado.")
        if (comOperador && !config.comOperador) {
            throw PrecisaDoPin("Digite o PIN para começar.", recusadoPeloServidor = false)
        }

        val requisicao = Request.Builder()
            .url(config.servidor + caminho)
            .header("Authorization", "Bearer ${config.token}")
            .apply { if (comOperador) header("X-Operador", config.operador) }
            .apply { if (corpo != null) post(corpo.toRequestBody(tipoJson)) else get() }
            .build()

        try {
            cliente.newCall(requisicao).execute().use { resposta ->
                val texto = resposta.body?.string().orEmpty()
                if (!resposta.isSuccessful) {
                    // A mensagem do servidor é escrita para a tela ("Falta
                    // receber só R$ 12,30"); melhor que "servidor respondeu 409".
                    val doServidor = try {
                        json.decodeFromString(ErroDoServidor.serializer(), texto).erro
                    } catch (e: Exception) {
                        ""
                    }
                    // 401 tem nome próprio: numa chamada de operador é o turno
                    // que venceu; nas outras, é o token do aparelho.
                    if (resposta.code == 401) {
                        if (comOperador) {
                            throw PrecisaDoPin(
                                doServidor.ifBlank { "Turno encerrado." },
                                recusadoPeloServidor = true,
                            )
                        }
                        throw FalhaNaApi(doServidor.ifBlank { "Token recusado pelo servidor." })
                    }
                    throw FalhaNaApi(doServidor.ifBlank { "Servidor respondeu ${resposta.code}." })
                }
                leitura(texto)
            }
        } catch (e: FalhaNaApi) {
            throw e
        } catch (e: PrecisaDoPin) {
            throw e
        } catch (e: IOException) {
            throw FalhaNaApi("Sem resposta do servidor.", e)
        }
    }

    /**
     * Até 20 trabalhos pendentes, do mais antigo para o mais novo.
     *
     * `colunas` diz ao servidor a largura desta impressora, e ele monta o papel
     * nela — é o que evita refluir aqui um texto já formatado em 48. Servidor
     * antigo ignora o parâmetro e devolve 48 como sempre.
     */
    suspend fun buscarFilaDeImpressao(colunas: Int): RespostaDaFila =
        chamar("/api/impressao?colunas=$colunas") {
            json.decodeFromString(RespostaDaFila.serializer(), it)
        }
            .also { if (it.unidade.isNotBlank()) configuracao.guardarUnidade(it.unidade) }

    /** Confirma o que saiu — ou relata por que não saiu. */
    suspend fun confirmarImpressao(id: String, ok: Boolean, erro: String? = null) {
        val corpo = json.encodeToString(
            ConfirmacaoDeImpressao.serializer(),
            ConfirmacaoDeImpressao(id = id, ok = ok, erro = erro),
        )
        chamar("/api/impressao", corpo) { }
    }

    /** O PIN vira o turno do operador; o PIN em si não fica guardado. */
    suspend fun entrarComPin(pin: String): RespostaDoOperador {
        val corpo = json.encodeToString(PinDoOperador.serializer(), PinDoOperador(pin))
        return chamar("/api/maquininha/entrar", corpo) {
            json.decodeFromString(RespostaDoOperador.serializer(), it)
        }.also { configuracao.guardarOperador(it.token, it.nome) }
    }

    /** As contas abertas do salão. */
    suspend fun buscarMesas(): RespostaDeMesas =
        chamar("/api/maquininha/mesas", comOperador = true) {
            json.decodeFromString(RespostaDeMesas.serializer(), it)
        }.also { if (it.unidade.isNotBlank()) configuracao.guardarUnidade(it.unidade) }

    /**
     * Abre a mesa pelo aparelho.
     *
     * Insistir é seguro: mesa que já tem conta devolve a conta que existe, com
     * `jaEstavaAberta`. Por isso uma resposta perdida na rede não vira duas
     * contas na mesma mesa quando o garçom toca de novo.
     */
    suspend fun abrirMesa(abertura: AberturaDeMesa): RespostaDaAbertura {
        val corpo = json.encodeToString(AberturaDeMesa.serializer(), abertura)
        return chamar("/api/maquininha/mesas", corpo, comOperador = true) {
            json.decodeFromString(RespostaDaAbertura.serializer(), it)
        }
    }

    /**
     * O cardápio do salão, inteiro de uma vez.
     *
     * É o mesmo cardápio do PDV, com o preço de lá. Vem completo numa resposta
     * só porque o garçom no salão não pode depender da rede a cada toque para
     * ver a próxima seção.
     */
    suspend fun buscarCardapio(): RespostaDoCardapio =
        chamar("/api/maquininha/cardapio", comOperador = true) {
            json.decodeFromString(RespostaDoCardapio.serializer(), it)
        }

    /** Manda a rodada que o garçom montou na mesa. */
    suspend fun lancarRodada(comandaId: String, rodada: RodadaParaLancar): RespostaDaRodada {
        val corpo = json.encodeToString(RodadaParaLancar.serializer(), rodada)
        return chamar("/api/maquininha/comandas/$comandaId/itens", corpo, comOperador = true) {
            json.decodeFromString(RespostaDaRodada.serializer(), it)
        }
    }

    /** A conta de uma mesa: itens, o que já foi pago e o que falta. */
    suspend fun buscarConta(comandaId: String): ContaDaMesa =
        chamar("/api/maquininha/comandas/$comandaId", comOperador = true) {
            json.decodeFromString(ContaDaMesa.serializer(), it)
        }

    /**
     * Conta ao servidor o pagamento que a credenciadora já aprovou.
     *
     * É a chamada que não pode se perder: uma transação aprovada que o
     * servidor não soube deixa a conta aberta no salão, e alguém cobra de
     * novo. Quem chama insiste até conseguir — veja `EnvioTeimoso`. Reenviar
     * é seguro: a referência faz o servidor reconhecer o mesmo pagamento.
     */
    suspend fun enviarPagamento(pagamento: PagamentoParaEnviar): RespostaDoPagamento {
        val corpo = json.encodeToString(PagamentoParaEnviar.serializer(), pagamento)
        return chamar("/api/maquininha/pagamentos", corpo, comOperador = true) {
            json.decodeFromString(RespostaDoPagamento.serializer(), it)
        }
    }
}
