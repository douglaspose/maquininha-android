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
     * O servidor mandou pedir a liberação de quem pode — não é recusa.
     *
     * Tem nome próprio porque a tela faz coisas opostas nos dois casos: numa
     * recusa ela mostra o vermelho e para; aqui ela abre o teclado para o
     * gerente digitar o PIN dele e segue de onde parou.
     */
    class PrecisaDeLiberacao(val tipo: String, mensagem: String) : IOException(mensagem)

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
        /**
         * O crachá de um turno que não é o de agora.
         *
         * Serve ao pagamento que ficou na fila: ele sai com o crachá de quem
         * cobrou, e não de quem estiver no aparelho na hora em que a rede
         * voltar — senão a venda entra no nome da pessoa errada, e é por esse
         * nome que a casa confere o caixa no fim do turno.
         */
        crachaDeOutroTurno: String? = null,
        leitura: (String) -> T,
    ): T = withContext(Dispatchers.IO) {
        val config = configuracao.atual()
        if (!config.completa) throw FalhaNaApi("Aparelho sem servidor ou token configurado.")
        val cracha = crachaDeOutroTurno ?: config.operador
        if (comOperador && cracha.isBlank()) {
            throw PrecisaDoPin("Digite o PIN para começar.", recusadoPeloServidor = false)
        }

        val requisicao = Request.Builder()
            .url(config.servidor + caminho)
            .header("Authorization", "Bearer ${config.token}")
            .apply { if (comOperador) header("X-Operador", cracha) }
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
                    // 403 com um tipo de autorização não é "você não pode":
                    // é "peça a quem pode", e a tela sabe o que fazer com isso.
                    val liberacao = try {
                        json.decodeFromString(ErroDoServidor.serializer(), texto).precisaAutorizacao
                    } catch (e: Exception) {
                        null
                    }
                    if (resposta.code == 403 && liberacao != null) {
                        throw PrecisaDeLiberacao(
                            liberacao,
                            doServidor.ifBlank { "Esta ação precisa de autorização." },
                        )
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
        } catch (e: PrecisaDeLiberacao) {
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
        // Duas coisas vão junto na busca:
        //
        //  - o apelido, para a fila saber quem levou cada papel — ela entrega
        //    cada trabalho a um agente só, e a tela de impressão mostra qual
        //    aparelho está puxando;
        //  - `embutida`, porque este aparelho imprime na bobina da mão e em
        //    mais lugar nenhum. Sem isso ele levaria a comanda endereçada à
        //    térmica da cozinha, e a praça ficaria sem o papel: ele sairia no
        //    bolso do garçom.
        chamar("/api/impressao?colunas=$colunas&embutida=1&agente=${configuracao.agente()}") {
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

    /**
     * A mesa pediu a conta: entra em fechamento e a folha vai para a impressora.
     *
     * Repetir é esperado — "sumiu a conta", "manda outra via" —, e a partir da
     * segunda o papel sai marcado como segunda via.
     */
    suspend fun pedirAConta(comandaId: String): RespostaDaConferencia =
        chamar("/api/maquininha/comandas/$comandaId/conferencia", "{}", comOperador = true) {
            json.decodeFromString(RespostaDaConferencia.serializer(), it)
        }

    /**
     * Muda a conta de mesa.
     *
     * Insistir é seguro: a conta que já está na mesa pedida volta com
     * `mudou: false` em vez de uma segunda transferência no diário.
     */
    suspend fun transferirMesa(comandaId: String, mesaDestinoId: String): RespostaDaTransferencia {
        val corpo = json.encodeToString(MesaDeDestino.serializer(), MesaDeDestino(mesaDestinoId))
        return chamar("/api/maquininha/comandas/$comandaId/transferir", corpo, comOperador = true) {
            json.decodeFromString(RespostaDaTransferencia.serializer(), it)
        }
    }

    /**
     * Tira um item da conta.
     *
     * Quem não tem a permissão recebe `PrecisaDeLiberacao` em vez de recusa: a
     * tela pede o PIN de quem pode e repete a chamada com a liberação.
     */
    suspend fun cancelarItem(
        itemId: String,
        motivo: String,
        autorizacaoId: String? = null,
    ): RespostaSimples {
        val corpo = json.encodeToString(
            CancelamentoDeItem.serializer(),
            CancelamentoDeItem(motivo, autorizacaoId),
        )
        return chamar("/api/maquininha/itens/$itemId/cancelar", corpo, comOperador = true) {
            json.decodeFromString(RespostaSimples.serializer(), it)
        }
    }

    /**
     * O gerente libera com o PIN dele, no aparelho que está na mão do garçom.
     *
     * PIN errado não é exceção: volta `ok = false` com o motivo pronto para a
     * tela. Tratar como falha mandaria o garçom embora da tela do item.
     */
    suspend fun pedirLiberacao(tipo: String, referenciaId: String, pin: String): RespostaDaLiberacao {
        val corpo = json.encodeToString(
            PedidoDeLiberacao.serializer(),
            PedidoDeLiberacao(tipo, referenciaId, pin),
        )
        return chamar("/api/maquininha/autorizacoes", corpo, comOperador = true) {
            json.decodeFromString(RespostaDaLiberacao.serializer(), it)
        }
    }

    /** Alguém foi até a mesa: o selo do chamado apaga. */
    suspend fun atenderChamado(comandaId: String): RespostaDoAtendimento =
        chamar("/api/maquininha/comandas/$comandaId/atender", "{}", comOperador = true) {
            json.decodeFromString(RespostaDoAtendimento.serializer(), it)
        }

    /** "Traz mais uma cerveja": a conta volta a aceitar item. */
    suspend fun reabrirConta(comandaId: String): RespostaDaReabertura =
        chamar("/api/maquininha/comandas/$comandaId/reabrir", "{}", comOperador = true) {
            json.decodeFromString(RespostaDaReabertura.serializer(), it)
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
    suspend fun enviarPagamento(
        pagamento: PagamentoParaEnviar,
        /** O crachá de quem cobrou, quando o pagamento vem da fila. */
        cracha: String? = null,
    ): RespostaDoPagamento {
        val corpo = json.encodeToString(PagamentoParaEnviar.serializer(), pagamento)
        return chamar(
            "/api/maquininha/pagamentos",
            corpo,
            comOperador = true,
            crachaDeOutroTurno = cracha,
        ) {
            json.decodeFromString(RespostaDoPagamento.serializer(), it)
        }
    }
}
