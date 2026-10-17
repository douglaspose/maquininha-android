package br.com.gestaorestaurante.maquininha.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import br.com.gestaorestaurante.maquininha.PlataformaAtual
import br.com.gestaorestaurante.maquininha.api.Api
import br.com.gestaorestaurante.maquininha.api.CategoriaDoCardapio
import br.com.gestaorestaurante.maquininha.api.ContaDaMesa
import br.com.gestaorestaurante.maquininha.api.ItemDaConta
import br.com.gestaorestaurante.maquininha.api.ItemDoCardapio
import br.com.gestaorestaurante.maquininha.api.EscolhaParaLancar
import br.com.gestaorestaurante.maquininha.api.ItemParaLancar
import br.com.gestaorestaurante.maquininha.api.OpcionalDoCardapio
import br.com.gestaorestaurante.maquininha.api.AberturaDeMesa
import br.com.gestaorestaurante.maquininha.api.MesaAberta
import br.com.gestaorestaurante.maquininha.api.MesaLivre
import br.com.gestaorestaurante.maquininha.api.PagamentoParaEnviar
import br.com.gestaorestaurante.maquininha.api.RodadaParaLancar
import br.com.gestaorestaurante.maquininha.config.Configuracao
import br.com.gestaorestaurante.maquininha.config.RepositorioDeConfiguracao
import br.com.gestaorestaurante.maquininha.impressao.ServicoDaFila
import br.com.gestaorestaurante.maquininha.impressao.lerConteudoDaFila
import br.com.gestaorestaurante.maquininha.impressao.paraLargura
import br.com.gestaorestaurante.maquininha.pagamento.AndamentoDaCobranca
import br.com.gestaorestaurante.maquininha.pagamento.Cobranca
import br.com.gestaorestaurante.maquininha.pagamento.DepositoEmDisco
import br.com.gestaorestaurante.maquininha.pagamento.EnvioTeimoso
import br.com.gestaorestaurante.maquininha.pagamento.TurnoVencido
import br.com.gestaorestaurante.maquininha.pagamento.FormaDePagamento
import br.com.gestaorestaurante.maquininha.pagamento.ResultadoDaCobranca
import br.com.gestaorestaurante.maquininha.pagamento.TransacaoAprovada
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** Em que ponto da venda a tela está. */
sealed interface EstadoDaTela {
    /**
     * O estado de quem acabou de abrir e ainda não leu a configuração do
     * disco. Dura menos de um segundo, mas sem ele a maquininha pisca a tela
     * de cobrança antes de cair nos ajustes — e quem está configurando o
     * aparelho vê uma tela que não pode usar.
     */
    data object Abrindo : EstadoDaTela
    data object Configurando : EstadoDaTela

    /** Começo de turno: a maquininha não abre o salão sem saber quem recebe. */
    data object PedindoPin : EstadoDaTela

    /** As mesas abertas, que é por onde o caixa começa. */
    data object Mesas : EstadoDaTela

    /** A conta de uma mesa: itens, o que falta, e por onde se decide o valor. */
    data object Conta : EstadoDaTela

    /** O cardápio, para o garçom montar a rodada sem voltar ao balcão. */
    data object Cardapio : EstadoDaTela

    /**
     * As mesas livres, para o garçom que recebe quem acabou de sentar.
     *
     * São duas telas, e não uma: escolher a mesa é procurar um número entre
     * dezenas, e dizer quantas pessoas são é responder uma pergunta. Juntas
     * numa tela só, a lista de mesas empurrava o resto para fora do visor da
     * maquininha.
     */
    data object EscolhendoMesa : EstadoDaTela

    /** Quantas pessoas (e o nome, onde a casa exige) para a mesa escolhida. */
    data object AbrindoMesa : EstadoDaTela

    /**
     * Para onde a conta vai. É a mesma grade de mesas livres da abertura, de
     * propósito: "escolher uma mesa vazia" é o mesmo gesto nas duas vezes.
     */
    data object EscolhendoDestino : EstadoDaTela

    /** Tirar um item da conta: o motivo, e o PIN de quem pode, se precisar. */
    data object Cancelando : EstadoDaTela

    /** O teclado do valor. Vem de uma conta, ou da cobrança avulsa. */
    data object Parada : EstadoDaTela
    data class Cobrando(val mensagem: String) : EstadoDaTela
    data class Terminada(
        val resultado: ResultadoDaCobranca,
        val enviado: Boolean,
        /** O que o servidor respondeu quando o pagamento era de uma conta. */
        val quitou: Boolean = false,
        val faltaEmCentavos: Long = 0,
    ) : EstadoDaTela
}

data class Painel(
    val configuracao: Configuracao = Configuracao(),
    val credenciadora: String = "",
    val provedorPronto: Boolean = false,
    val motivoDoProvedor: String = "",
    val estado: EstadoDaTela = EstadoDaTela.Abrindo,
    val valorEmCentavos: Long = 0,
    val forma: FormaDePagamento = FormaDePagamento.CREDITO,
    val parcelas: Int = 1,
    val pendentesDeEnvio: Int = 0,
    /** O salão, como o servidor respondeu da última vez. */
    val mesas: List<MesaAberta> = emptyList(),
    /** As mesas sem conta aberta, como o servidor respondeu da última vez. */
    val livres: List<MesaLivre> = emptyList(),
    /** Sem a permissão de abrir, o botão nem aparece. */
    val podeAbrir: Boolean = false,
    /** Sem a permissão de receber, a tela não oferece onde cobrar. */
    val podeReceber: Boolean = true,
    /** Sem a permissão de pedir a conta, a tela não oferece o papel. */
    val podePedirConta: Boolean = false,
    /** Sem a permissão de transferir, a tela não oferece mudar de mesa. */
    val podeTransferir: Boolean = false,
    /** Sem isto, as linhas da conta não são tocáveis: não há o que fazer com elas. */
    val podeCancelar: Boolean = false,
    /** A casa exige o nome do cliente: o campo aparece e a abertura o cobra. */
    val exigeNomeCliente: Boolean = false,
    /** A mesa que está sendo aberta, entre escolher o número e confirmar. */
    val mesaParaAbrir: MesaLivre? = null,
    /** O item que está sendo cancelado, enquanto a tela do motivo está aberta. */
    val itemParaCancelar: ItemDaConta? = null,
    /**
     * O servidor pediu a liberação de quem pode cancelar: a tela troca o
     * motivo pelo teclado do PIN. Não é erro, e por isso não é `aviso`.
     */
    val pedindoLiberacao: Boolean = false,
    val caixaAberto: Boolean = true,
    val buscando: Boolean = false,
    val conta: ContaDaMesa? = null,
    /** A conta que este valor vai pagar. Nulo na cobrança avulsa. */
    val comandaId: String? = null,
    /** Recado curto para a tela: o que o servidor recusou, ou a rede que caiu. */
    val aviso: String? = null,
    /**
     * O que deu certo, quando dar certo não aparece sozinho na tela.
     *
     * Separado do `aviso` porque este é vermelho: "segunda via na impressora"
     * não é falha, e pintar as duas coisas de vermelho ensina o garçom a não
     * ler nenhuma. Nasceu do toque em "Imprimir outra via", que acerta tudo e
     * não muda nada no visor — o papel sai na impressora do salão, longe dali.
     */
    val recado: String? = null,
    /**
     * Sobe a cada PIN recusado. A tela limpa o campo quando ele muda — e não
     * quando o aviso muda, porque duas tentativas erradas seguidas dão a mesma
     * mensagem, e o campo precisa limpar nas duas.
     */
    val falhasNoPin: Int = 0,
    /** O cardápio da casa, como o servidor respondeu. */
    val cardapio: List<CategoriaDoCardapio> = emptyList(),
    /** A rodada que o garçom está montando, antes de mandar para a cozinha. */
    val carrinho: List<LinhaDoCarrinho> = emptyList(),
) {
    val totalDoCarrinhoEmCentavos: Long
        get() = carrinho.sumOf { it.precoDaLinhaEmCentavos }
}

/**
 * Uma linha da rodada em montagem.
 *
 * Mora só na memória da maquininha até o garçom mandar: enquanto ele escolhe,
 * nada disso existe para o resto da casa. O carrinho do banco — o que o PDV
 * mostra — só nasce quando a rodada é lançada.
 */
data class LinhaDoCarrinho(
    /**
     * Identidade própria, e não a posição na lista: com escolhas, o mesmo
     * produto aparece em várias linhas, e é por este id que a tela sabe de
     * qual delas tirar uma unidade ou em qual escrever a observação.
     */
    val id: String = UUID.randomUUID().toString(),
    val item: ItemDoCardapio,
    val quantidade: Int,
    /**
     * As escolhas desta linha. Linhas do mesmo produto com escolhas diferentes
     * são linhas diferentes — uma picanha com bacon não é a mesma coisa que
     * uma sem, nem no preço nem no papel da cozinha.
     */
    val escolhas: List<OpcionalDoCardapio> = emptyList(),
    /**
     * Vale para a linha inteira, não por unidade: "sem cebola" é do prato, e
     * o que muda de um para o outro na mesma mesa — o ponto da carne — tem
     * campo próprio no sistema. Para duas observações diferentes do mesmo
     * item, o garçom lança em rodadas separadas.
     */
    val observacao: String? = null,
) {
    /**
     * O que esta linha custa, com os adicionais somados ao item.
     *
     * A mesma conta que o servidor faz — e é de propósito que ela apareça
     * antes de mandar: o garçom confere o total da rodada na maquininha, e
     * depois o cliente confere o mesmo número na conta.
     */
    val precoDaLinhaEmCentavos: Long
        get() = (item.precoEmCentavos + escolhas.sumOf { it.precoEmCentavos }) * quantidade
}

class ModeloDaTela(aplicacao: Application) : AndroidViewModel(aplicacao) {

    private val contexto = aplicacao.applicationContext
    private val configuracao = RepositorioDeConfiguracao(contexto)
    private val api = Api(configuracao)
    /*
   * A fila manda pela `api`, e traduz a recusa de turno para a linguagem dela.
   *
   * `PrecisaDoPin` quer dizer duas coisas no aparelho — o servidor recusou o
   * crachá, ou nem havia crachá para mandar —, e para a fila as duas são a
   * mesma: este pagamento espera a pessoa dele voltar, enquanto os outros
   * seguem saindo.
   */
    private val envio = EnvioTeimoso(DepositoEmDisco(contexto)) { pagamento, cracha ->
        try {
            api.enviarPagamento(pagamento, cracha)
        } catch (e: Api.PrecisaDoPin) {
            throw TurnoVencido(e.message ?: "Turno vencido.")
        }
    }
    private val provedor = PlataformaAtual.pagamento(contexto)
    private val impressora = PlataformaAtual.impressora(contexto)

    private val _painel = MutableStateFlow(Painel(credenciadora = PlataformaAtual.credenciadora))
    val painel: StateFlow<Painel> = _painel.asStateFlow()

    init {
        viewModelScope.launch {
            val config = configuracao.atual()
            _painel.update { it.copy(configuracao = config, estado = primeiraTela(config)) }
            if (config.completa) {
                ServicoDaFila.ligar(contexto)
                // Pagamento que ficou preso numa queda de rede sai agora, antes
                // de qualquer venda nova.
                envio.escoar()
                _painel.update { it.copy(pendentesDeEnvio = envio.quantasPendentes()) }
                if (config.comOperador) buscarMesas()
            }
            prepararProvedor()
        }
        viewModelScope.launch {
            configuracao.fluxo.collect { config ->
                _painel.update { it.copy(configuracao = config) }
            }
        }
    }

    /**
     * Por onde o aplicativo abre: ajustes sem token, PIN sem turno, e o salão
     * quando já sabe as duas coisas.
     */
    private fun primeiraTela(config: Configuracao) = when {
        !config.completa -> EstadoDaTela.Configurando
        !config.comOperador -> EstadoDaTela.PedindoPin
        else -> EstadoDaTela.Mesas
    }

    private suspend fun prepararProvedor() {
        val pronto = try {
            provedor.preparar()
        } catch (e: Exception) {
            _painel.update { it.copy(motivoDoProvedor = e.message ?: "falhou ao iniciar") }
            false
        }
        _painel.update {
            it.copy(
                provedorPronto = pronto,
                motivoDoProvedor = if (pronto) "" else it.motivoDoProvedor.ifBlank {
                    "Aparelho nao ativado na " + provedor.nome + "."
                },
            )
        }
    }

    /** Começo de turno: o PIN vira o token que vale as próximas doze horas. */
    fun entrarComPin(pin: String) {
        viewModelScope.launch {
            _painel.update { it.copy(buscando = true, aviso = null) }
            try {
                api.entrarComPin(pin)
                _painel.update { it.copy(estado = EstadoDaTela.Mesas, buscando = false) }
                buscarMesas()
            } catch (e: Exception) {
                _painel.update {
                    it.copy(
                        buscando = false,
                        aviso = e.message ?: "Não foi possível entrar.",
                        falhasNoPin = it.falhasNoPin + 1,
                    )
                }
            }
        }
    }

    /** Fim de turno: o próximo operador digita o PIN dele. */
    fun sairDoTurno() {
        viewModelScope.launch {
            configuracao.esquecerOperador("o operador saiu pelo botão")
            _painel.update {
                it.copy(estado = EstadoDaTela.PedindoPin, mesas = emptyList(), conta = null)
            }
        }
    }

    fun atualizarMesas() {
        viewModelScope.launch { buscarMesas() }
    }

    private suspend fun buscarMesas(segundaTentativa: Boolean = false) {
        _painel.update { it.copy(buscando = true) }
        try {
            val resposta = api.buscarMesas()
            /*
             * O salão respondeu, então a rede voltou: é a hora de escoar o que
             * ficou para trás. Antes isso só acontecia na abertura do
             * aplicativo, e a maquininha fica aberta a noite inteira — um
             * pagamento que caiu na fila às nove da noite esperava alguém
             * reiniciar o aparelho, enquanto a tela prometia que ele sairia
             * sozinho quando a rede voltasse.
             */
            escoarPendentes()
            _painel.update {
                it.copy(
                    mesas = resposta.mesas,
                    livres = resposta.livres,
                    podeAbrir = resposta.podeAbrir,
                    podeReceber = resposta.podeReceber,
                    podePedirConta = resposta.podePedirConta,
                    podeTransferir = resposta.podeTransferir,
                    podeCancelar = resposta.podeCancelar,
                    exigeNomeCliente = resposta.exigeNomeCliente,
                    caixaAberto = resposta.caixaAberto,
                    buscando = false,
                    aviso = null,
                )
            }
        } catch (e: Api.PrecisaDoPin) {
            /*
             * Duas coisas diferentes chegam aqui, e só uma custa o turno.
             *
             * Recusado pelo servidor é definitivo: o token não serve mais, e
             * guardá-lo só atrapalha. Mas quando a própria maquininha viu que
             * não tinha turno — sem falar com ninguém — e o turno está no
             * disco, o que houve foi a configuração ser lida no instante
             * errado. Aí a resposta certa é tentar de novo, não mandar o caixa
             * redigitar o PIN no meio do salão.
             */
            if (!e.recusadoPeloServidor && !segundaTentativa && configuracao.atual().comOperador) {
                buscarMesas(segundaTentativa = true)
                return
            }
            if (e.recusadoPeloServidor) {
                configuracao.esquecerOperador("o servidor recusou o turno (${e.message})")
            }
            _painel.update {
                it.copy(estado = EstadoDaTela.PedindoPin, buscando = false, aviso = e.message)
            }
        } catch (e: Exception) {
            _painel.update { it.copy(buscando = false, aviso = e.message ?: "Sem resposta do servidor.") }
        }
    }

    /**
     * Vai para a lista de mesas livres.
     *
     * A lista que já veio com o salão é a que aparece — e não uma busca nova,
     * que deixaria o garçom olhando "carregando" com a mesa na frente dele. Ela
     * é atualizada a cada volta ao salão e a cada abertura.
     */
    fun irParaAbertura() {
        _painel.update {
            it.copy(estado = EstadoDaTela.EscolhendoMesa, mesaParaAbrir = null, aviso = null)
        }
    }

    fun escolherMesaLivre(mesa: MesaLivre) {
        _painel.update { it.copy(estado = EstadoDaTela.AbrindoMesa, mesaParaAbrir = mesa, aviso = null) }
    }

    /**
     * Abre a mesa escolhida e cai direto na conta dela.
     *
     * Cair na conta não é economia de toque: o garçom que abre a mesa está
     * abrindo porque vai lançar o pedido agora, e voltar para a lista o faria
     * procurar a mesma mesa de novo na lista de cima.
     *
     * Insistir depois de uma falha é seguro: se a mesa já tiver conta — porque
     * a resposta se perdeu na rede, ou porque outro garçom chegou junto —, o
     * servidor devolve a conta que existe em vez de abrir a segunda.
     */
    fun abrirMesa(pessoas: Int, nomeCliente: String) {
        val mesa = _painel.value.mesaParaAbrir ?: return
        viewModelScope.launch {
            _painel.update { it.copy(buscando = true, aviso = null) }
            try {
                val resposta = api.abrirMesa(
                    AberturaDeMesa(
                        mesaId = mesa.mesaId,
                        pessoas = pessoas,
                        nomeCliente = nomeCliente.trim().ifBlank { null },
                    )
                )
                _painel.update { it.copy(mesaParaAbrir = null) }
                carregarConta(resposta.comandaId)
                // O salão mudou: a mesa que era livre agora é conta aberta.
                buscarMesas()
            } catch (e: Exception) {
                _painel.update {
                    it.copy(buscando = false, aviso = e.message ?: "Não foi possível abrir a mesa.")
                }
            }
        }
    }

    /**
     * Manda o que ficou para trás e atualiza o contador da tela.
     *
     * Silencioso de propósito: falhar aqui não é novidade para quem está
     * olhando — a tela já mostra quantos estão esperando, e o escoamento tenta
     * de novo na próxima resposta do salão.
     */
    private suspend fun escoarPendentes() {
        try {
            if (envio.escoar() > 0) {
                _painel.update { it.copy(pendentesDeEnvio = envio.quantasPendentes()) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Maquininha", "não deu para escoar os pendentes agora: ${e.message}")
        }
    }

    /** Abre a conta de uma mesa: itens, o que já foi pago e o que falta. */
    fun abrirConta(comandaId: String) {
        viewModelScope.launch { carregarConta(comandaId) }
    }

    /*
     * Suspensa, e não uma corrotina própria: a abertura de mesa precisa esperar
     * a conta aparecer para só então atualizar o salão. Soltas as duas juntas,
     * a atualização do salão terminava primeiro, desligava o `buscando` e a
     * tela da conta ficava vazia e sem sinal de que ainda estava carregando.
     */
    private suspend fun carregarConta(comandaId: String) {
        _painel.update {
            it.copy(
                estado = EstadoDaTela.Conta,
                buscando = true,
                conta = null,
                aviso = null,
                recado = null,
            )
        }
        try {
            val conta = api.buscarConta(comandaId)
            _painel.update { it.copy(conta = conta, comandaId = comandaId, buscando = false) }
        } catch (e: Exception) {
            _painel.update {
                it.copy(estado = EstadoDaTela.Mesas, buscando = false, aviso = e.message)
            }
        }
    }

    /**
     * A mesa pediu a conta: fechamento marcado e papel na impressora.
     *
     * Depois, a conta é relida: é ela que diz à tela que agora está em
     * fechamento, e portanto que o caminho dali é reabrir ou cobrar. O salão
     * também, porque a mesa mudou de cor no mapa de quem está no balcão.
     *
     * Insistir é seguro e é rotina: a segunda folha sai marcada como segunda
     * via, e o aviso diz isso — senão o garçom fica sem saber se o toque pegou.
     */
    fun pedirAConta() {
        val comandaId = _painel.value.comandaId ?: return
        viewModelScope.launch {
            _painel.update { it.copy(buscando = true, aviso = null) }
            try {
                val resposta = api.pedirAConta(comandaId)
                // O recado vem depois de reler a conta: é a releitura que o
                // apaga, e o garçom tem de ficar com ele na tela.
                carregarConta(comandaId)
                buscarMesas()
                _painel.update {
                    it.copy(
                        buscando = false,
                        recado = if (resposta.segundaVia) {
                            "Segunda via na impressora."
                        } else {
                            "Conta pedida. A folha está saindo."
                        },
                    )
                }
            } catch (e: Exception) {
                _painel.update {
                    it.copy(buscando = false, aviso = e.message ?: "Não foi possível pedir a conta.")
                }
            }
        }
    }

    /** Abre a tela do motivo para tirar este item da conta. */
    fun irParaCancelamento(item: ItemDaConta) {
        _painel.update {
            it.copy(
                estado = EstadoDaTela.Cancelando,
                itemParaCancelar = item,
                pedindoLiberacao = false,
                aviso = null,
            )
        }
    }

    /**
     * Tira o item da conta.
     *
     * Quem não tem a permissão não é recusado: o servidor responde que precisa
     * de liberação, e a tela troca o motivo pelo teclado do PIN sem perder o
     * que o garçom já escreveu. É o gerente digitando no aparelho dele, de pé
     * na mesa, em vez de os dois irem até o balcão.
     */
    fun cancelarItem(motivo: String, autorizacaoId: String? = null) {
        val item = _painel.value.itemParaCancelar ?: return
        viewModelScope.launch {
            _painel.update { it.copy(buscando = true, aviso = null) }
            try {
                api.cancelarItem(item.itemId, motivo, autorizacaoId)
                val comandaId = _painel.value.comandaId
                _painel.update {
                    it.copy(
                        estado = EstadoDaTela.Conta,
                        itemParaCancelar = null,
                        pedindoLiberacao = false,
                    )
                }
                if (comandaId != null) carregarConta(comandaId)
                buscarMesas()
                _painel.update { it.copy(buscando = false, recado = "Item cancelado.") }
            } catch (e: Api.PrecisaDeLiberacao) {
                _painel.update { it.copy(buscando = false, pedindoLiberacao = true, aviso = null) }
            } catch (e: Exception) {
                _painel.update {
                    it.copy(buscando = false, aviso = e.message ?: "Não foi possível cancelar.")
                }
            }
        }
    }

    /**
     * O gerente digita o PIN dele e, liberado, o cancelamento segue na hora.
     *
     * A liberação vale para este item e por poucos minutos: não adianta guardar
     * para o próximo, e é de propósito — o que o gerente autorizou foi isto.
     */
    fun liberarCancelamento(pin: String, motivo: String) {
        val item = _painel.value.itemParaCancelar ?: return
        viewModelScope.launch {
            _painel.update { it.copy(buscando = true, aviso = null) }
            try {
                val r = api.pedirLiberacao("CANCELAMENTO_ITEM", item.itemId, pin)
                if (!r.ok) {
                    _painel.update { it.copy(buscando = false, aviso = r.motivo) }
                    return@launch
                }
                cancelarItem(motivo, r.autorizacaoId)
            } catch (e: Exception) {
                _painel.update {
                    it.copy(buscando = false, aviso = e.message ?: "Não foi possível liberar.")
                }
            }
        }
    }

    /** Abre a grade de mesas livres para escolher o destino da conta. */
    fun irParaTransferencia() {
        _painel.update { it.copy(estado = EstadoDaTela.EscolhendoDestino, aviso = null) }
    }

    /**
     * Muda a conta de mesa e volta para ela, já no lugar novo.
     *
     * Voltar para a conta, e não para o salão: quem transferiu estava
     * atendendo aquela mesa e continua atendendo — o que mudou foi onde ela
     * está. O salão é relido porque duas mesas trocaram de estado.
     *
     * A recusa mais comum é a mesa ter sido ocupada no meio do caminho, e ela
     * volta do servidor como frase pronta ("A mesa 12 já tem a comanda #87
     * aberta."): a tela mostra e o garçom escolhe outra.
     */
    fun transferirPara(mesa: MesaLivre) {
        val comandaId = _painel.value.comandaId ?: return
        viewModelScope.launch {
            _painel.update { it.copy(buscando = true, aviso = null) }
            try {
                val resposta = api.transferirMesa(comandaId, mesa.mesaId)
                _painel.update { it.copy(estado = EstadoDaTela.Conta) }
                carregarConta(comandaId)
                buscarMesas()
                _painel.update {
                    it.copy(
                        buscando = false,
                        recado = if (resposta.mudou) "Conta na mesa ${resposta.mesa}." else null,
                    )
                }
            } catch (e: Exception) {
                _painel.update {
                    it.copy(buscando = false, aviso = e.message ?: "Não foi possível transferir.")
                }
            }
        }
    }

    /**
     * Alguém foi até a mesa: apaga o selo do chamado.
     *
     * Some da lista do salão — daqui e do tablet do balcão, que recebe o aviso
     * no mesmo instante. É o que impede dois garçons de irem à mesma mesa.
     */
    fun atenderChamado() {
        val comandaId = _painel.value.comandaId ?: return
        viewModelScope.launch {
            _painel.update { it.copy(buscando = true, aviso = null) }
            try {
                api.atenderChamado(comandaId)
                carregarConta(comandaId)
                buscarMesas()
            } catch (e: Exception) {
                _painel.update {
                    it.copy(buscando = false, aviso = e.message ?: "Não foi possível atender.")
                }
            }
        }
    }

    /** "Traz mais uma cerveja" depois de pedir a conta. */
    fun reabrirConta() {
        val comandaId = _painel.value.comandaId ?: return
        viewModelScope.launch {
            _painel.update { it.copy(buscando = true, aviso = null) }
            try {
                api.reabrirConta(comandaId)
                _painel.update { it.copy(buscando = false) }
                carregarConta(comandaId)
                buscarMesas()
            } catch (e: Exception) {
                _painel.update {
                    it.copy(buscando = false, aviso = e.message ?: "Não foi possível reabrir.")
                }
            }
        }
    }

    /**
     * Abre o cardápio para a conta que está na tela.
     *
     * O cardápio é buscado uma vez por turno e fica: ele muda quando a casa
     * mexe nele, não entre uma mesa e outra, e numa noite cheia são dezenas de
     * idas ao cardápio que não precisam de rede.
     */
    fun abrirCardapio() {
        val comandaId = _painel.value.comandaId ?: return
        viewModelScope.launch {
            _painel.update {
                it.copy(estado = EstadoDaTela.Cardapio, carrinho = emptyList(), aviso = null)
            }
            if (_painel.value.cardapio.isNotEmpty()) return@launch

            _painel.update { it.copy(buscando = true) }
            try {
                val cardapio = api.buscarCardapio()
                _painel.update { it.copy(cardapio = cardapio.categorias, buscando = false) }
            } catch (e: Exception) {
                _painel.update {
                    it.copy(estado = EstadoDaTela.Conta, buscando = false, aviso = e.message)
                }
            }
        }
    }

    /**
     * Mais um deste item na rodada, com as escolhas que vieram do diálogo.
     *
     * Escolhas iguais somam na mesma linha; diferentes abrem outra. Duas
     * picanhas ao ponto são "2x"; uma ao ponto e uma mal passada são duas
     * linhas, porque é o que a cozinha vai ler e o que a conta vai cobrar.
     */
    fun porNoCarrinho(item: ItemDoCardapio, escolhas: List<OpcionalDoCardapio> = emptyList()) {
        _painel.update { painel ->
            painel.copy(carrinho = porNaRodada(painel.carrinho, item, escolhas), aviso = null)
        }
    }

    /**
     * A observação da linha — o que vai escrito no papel da cozinha.
     *
     * Em branco, some: observação vazia gravada vira linha que não agrupa com
     * nenhuma outra, e o garçom não entende por que dois chopes aparecem
     * separados na conta.
     */
    fun anotarObservacao(linhaId: String, texto: String) {
        _painel.update { it.copy(carrinho = anotarNaRodada(it.carrinho, linhaId, texto)) }
    }

    /** Menos um nesta linha. Chegando a zero, a linha sai da rodada. */
    fun tirarDoCarrinho(linhaId: String) {
        _painel.update { it.copy(carrinho = tirarDaRodada(it.carrinho, linhaId)) }
    }

    /**
     * Manda a rodada para a cozinha e volta para a conta já atualizada.
     *
     * Volta buscando a conta de novo porque o que importa ao garçom logo
     * depois de lançar é o total novo — e porque é a confirmação de que o que
     * ele montou chegou mesmo.
     */
    fun lancarRodada() {
        val painel = _painel.value
        val comandaId = painel.comandaId ?: return
        if (painel.carrinho.isEmpty() || painel.buscando) return

        viewModelScope.launch {
            _painel.update { it.copy(buscando = true, aviso = null) }
            try {
                api.lancarRodada(
                    comandaId,
                    RodadaParaLancar(
                        itens = painel.carrinho.map { linha ->
                            ItemParaLancar(
                                cardapioItemId = linha.item.cardapioItemId,
                                /*
                                 * O id da linha é a referência, e não um valor
                                 * novo a cada envio: ele nasce quando o garçom
                                 * põe o item na rodada e sobrevive a quantas
                                 * tentativas forem necessárias. É isso que faz
                                 * o reenvio não lançar o pedido duas vezes.
                                 */
                                referencia = linha.id,
                                quantidade = linha.quantidade,
                                observacao = linha.observacao,
                                opcionais = linha.escolhas.map {
                                    EscolhaParaLancar(it.opcionalId)
                                },
                            )
                        },
                    ),
                )
                _painel.update { it.copy(carrinho = emptyList(), buscando = false) }
                abrirConta(comandaId)
            } catch (e: Exception) {
                /*
                 * O carrinho fica como está: a rodada pode ter entrado antes de
                 * a resposta se perder, e limpar a tela faria o garçom montar
                 * tudo de novo sem saber. Ele confere a conta e decide.
                 */
                _painel.update {
                    it.copy(buscando = false, aviso = e.message ?: "Não foi possível lançar.")
                }
            }
        }
    }

    /**
     * Do que falta na conta para o teclado.
     *
     * `tudo` leva o valor travado; parcial abre o teclado zerado, para o
     * cliente que paga a parte dele e vai embora.
     */
    fun cobrarDaConta(tudo: Boolean) {
        val conta = _painel.value.conta ?: return
        _painel.update {
            it.copy(
                estado = EstadoDaTela.Parada,
                comandaId = conta.comandaId,
                valorEmCentavos = if (tudo) conta.faltaEmCentavos else 0,
                aviso = null,
            )
        }
    }

    /** Cobrança avulsa: valor digitado, sem conta nenhuma no salão. */
    fun cobrancaAvulsa() {
        _painel.update {
            it.copy(estado = EstadoDaTela.Parada, comandaId = null, valorEmCentavos = 0, aviso = null)
        }
    }

    /** Volta uma tela, sem perder o turno. */
    fun voltar() {
        _painel.update {
            when (it.estado) {
                is EstadoDaTela.Conta -> it.copy(estado = EstadoDaTela.Mesas, conta = null)
                /*
                 * O cardápio volta para a conta, que é o que o botão dele diz
                 * desde sempre: ele caía aqui no `else` e levava o garçom para
                 * a lista de mesas: quem montou meia rodada e foi conferir o
                 * total voltava do começo, procurando a mesa de novo.
                 */
                is EstadoDaTela.Cardapio ->
                    if (it.comandaId != null) {
                        it.copy(estado = EstadoDaTela.Conta)
                    } else {
                        it.copy(estado = EstadoDaTela.Mesas)
                    }
                // A pergunta das pessoas volta para a escolha da mesa: errar o
                // número da mesa é o engano mais provável dos dois.
                is EstadoDaTela.AbrindoMesa ->
                    it.copy(estado = EstadoDaTela.EscolhendoMesa, mesaParaAbrir = null)
                // Escolher o destino volta para a conta, que é de onde se veio.
                is EstadoDaTela.EscolhendoDestino -> it.copy(estado = EstadoDaTela.Conta)
                is EstadoDaTela.Cancelando ->
                    it.copy(
                        estado = EstadoDaTela.Conta,
                        itemParaCancelar = null,
                        pedindoLiberacao = false,
                        aviso = null,
                    )
                is EstadoDaTela.Parada ->
                    if (it.comandaId != null) {
                        it.copy(estado = EstadoDaTela.Conta, valorEmCentavos = 0)
                    } else {
                        it.copy(estado = EstadoDaTela.Mesas, valorEmCentavos = 0)
                    }
                else -> it.copy(estado = EstadoDaTela.Mesas)
            }
        }
    }

    fun digitar(digito: Char) {
        // Tecla durante a transação não entra: a maquininha está com o cartão.
        if (_painel.value.estado is EstadoDaTela.Cobrando) return
        _painel.update { it.copy(valorEmCentavos = digitarCentavo(it.valorEmCentavos, digito)) }
    }

    fun apagar() {
        _painel.update { it.copy(valorEmCentavos = apagarCentavo(it.valorEmCentavos)) }
    }

    /**
     * Depois de uma venda: volta para o salão quando havia conta, e para o
     * teclado quando era cobrança avulsa. No meio da digitação, zera o valor.
     */
    fun limpar() {
        val painel = _painel.value
        if (painel.estado is EstadoDaTela.Terminada && painel.comandaId != null) {
            _painel.update { it.copy(valorEmCentavos = 0, conta = null, estado = EstadoDaTela.Mesas) }
            viewModelScope.launch { buscarMesas() }
            return
        }
        _painel.update { it.copy(valorEmCentavos = 0, estado = EstadoDaTela.Parada) }
    }

    fun escolherForma(forma: FormaDePagamento) {
        _painel.update {
            it.copy(
                forma = forma,
                // Parcelado começa em 2x: "parcelado em 1x" não existe, e
                // deixar em 1 fazia a tela pedir parcelas sem nenhuma marcada.
                parcelas = if (forma.parcela) maxOf(it.parcelas, 2) else 1,
            )
        }
    }

    fun escolherParcelas(parcelas: Int) {
        _painel.update { it.copy(parcelas = parcelas.coerceIn(1, 12)) }
    }

    fun configurar(servidor: String, token: String) {
        viewModelScope.launch {
            configuracao.guardar(servidor, token)
            val config = configuracao.atual()
            _painel.update { it.copy(configuracao = config, estado = primeiraTela(config)) }
            if (config.completa) ServicoDaFila.ligar(contexto)
        }
    }

    fun abrirConfiguracao() {
        _painel.update { it.copy(estado = EstadoDaTela.Configurando) }
    }

    fun cobrar() {
        val painel = _painel.value
        if (painel.valorEmCentavos <= 0 || painel.estado is EstadoDaTela.Cobrando) return

        viewModelScope.launch {
            _painel.update { it.copy(estado = EstadoDaTela.Cobrando("Iniciando")) }

            /*
             * A referência nasce aqui e vai junto para a credenciadora e para
             * o servidor. É ela que faz um reenvio — a rede caiu depois de a
             * cobrança ser aprovada — ser reconhecido como o mesmo pagamento,
             * em vez de virar um segundo.
             */
            val cobranca = Cobranca(
                referencia = UUID.randomUUID().toString(),
                valorEmCentavos = painel.valorEmCentavos,
                forma = painel.forma,
                parcelas = painel.parcelas,
            )

            val resultado = provedor.cobrar(cobranca) { andamento: AndamentoDaCobranca ->
                _painel.update { it.copy(estado = EstadoDaTela.Cobrando(andamento.mensagem)) }
            }

            val contado = contar(resultado, cobranca, painel.comandaId)
            if (resultado is ResultadoDaCobranca.Aprovada) imprimirComprovante(resultado.transacao)

            _painel.update {
                it.copy(
                    estado = EstadoDaTela.Terminada(
                        resultado = resultado,
                        enviado = contado.enviado,
                        quitou = contado.quitou,
                        faltaEmCentavos = contado.falta,
                    ),
                    pendentesDeEnvio = envio.quantasPendentes(),
                )
            }
        }
    }

    private data class Contado(val enviado: Boolean, val quitou: Boolean = false, val falta: Long = 0)

    /**
     * Conta ao servidor o pagamento aprovado, e insiste se precisar.
     *
     * Só o que foi aprovado **numa conta** vai: recusa não é pagamento, e
     * cobrança avulsa não tem conta onde entrar — essa fica só na
     * credenciadora, para a conciliação.
     */
    private suspend fun contar(
        resultado: ResultadoDaCobranca,
        cobranca: Cobranca,
        comandaId: String?,
    ): Contado {
        val aprovada = (resultado as? ResultadoDaCobranca.Aprovada)?.transacao
        if (aprovada == null || comandaId == null) return Contado(enviado = true)

        val pagamento = PagamentoParaEnviar(
            comandaId = comandaId,
            referencia = cobranca.referencia,
            forma = cobranca.forma.name,
            valorEmCentavos = aprovada.valorEmCentavos,
            nsu = aprovada.identificadorNaCredenciadora,
            bandeira = aprovada.bandeira,
        )

        /*
         * Tudo passa pela fila, inclusive a primeira tentativa.
         *
         * Antes, a primeira ia direto pela `api` e só a falha caía na fila —
         * e era justamente nessa primeira, com a rede ruim, que o aplicativo
         * fechado levava junto um pagamento já aprovado no cartão. Agora ele
         * está em disco antes de qualquer chamada: o pior caso virou sair duas
         * vezes para o servidor, que reconhece a referência e não duplica.
         */
        /*
         * O crachá de agora vai junto com o pagamento: é quem está cobrando.
         * Se a rede só voltar no turno seguinte, a venda entra no servidor no
         * nome de quem passou o cartão, e não de quem estiver com o aparelho.
         */
        val resposta = envio.enviar(pagamento, configuracao.atual().operador)
        return if (resposta == null) {
            // Ficou na fila: a tela avisa, porque a conta continua aberta no
            // salão até o escoamento conseguir.
            Contado(enviado = false)
        } else {
            Contado(enviado = true, quitou = resposta.quitada, falta = resposta.faltaEmCentavos)
        }
    }

    /** A via do cliente sai na hora; a da loja, so quando o caixa pedir. */
    private suspend fun imprimirComprovante(transacao: TransacaoAprovada) {
        val via = transacao.viaDoCliente ?: return
        val cupom = lerConteudoDaFila("Comprovante", via, null).paraLargura(impressora.colunas)
        impressora.imprimir(cupom)
    }
}

/** Credito parcelado e a unica forma que pergunta parcelas. */
val FormaDePagamento.parcela: Boolean
    get() = this == FormaDePagamento.CREDITO_PARCELADO
