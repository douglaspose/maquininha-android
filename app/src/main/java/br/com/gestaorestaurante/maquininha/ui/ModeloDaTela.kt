package br.com.gestaorestaurante.maquininha.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import br.com.gestaorestaurante.maquininha.PlataformaAtual
import br.com.gestaorestaurante.maquininha.api.Api
import br.com.gestaorestaurante.maquininha.api.CategoriaDoCardapio
import br.com.gestaorestaurante.maquininha.api.ContaDaMesa
import br.com.gestaorestaurante.maquininha.api.ItemDoCardapio
import br.com.gestaorestaurante.maquininha.api.ItemParaLancar
import br.com.gestaorestaurante.maquininha.api.MesaAberta
import br.com.gestaorestaurante.maquininha.api.PagamentoParaEnviar
import br.com.gestaorestaurante.maquininha.api.RodadaParaLancar
import br.com.gestaorestaurante.maquininha.config.Configuracao
import br.com.gestaorestaurante.maquininha.config.RepositorioDeConfiguracao
import br.com.gestaorestaurante.maquininha.impressao.ServicoDaFila
import br.com.gestaorestaurante.maquininha.impressao.lerConteudoDaFila
import br.com.gestaorestaurante.maquininha.impressao.paraLargura
import br.com.gestaorestaurante.maquininha.pagamento.AndamentoDaCobranca
import br.com.gestaorestaurante.maquininha.pagamento.Cobranca
import br.com.gestaorestaurante.maquininha.pagamento.EnvioTeimoso
import br.com.gestaorestaurante.maquininha.pagamento.FormaDePagamento
import br.com.gestaorestaurante.maquininha.pagamento.ResultadoDaCobranca
import br.com.gestaorestaurante.maquininha.pagamento.TransacaoAprovada
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
    val caixaAberto: Boolean = true,
    val buscando: Boolean = false,
    val conta: ContaDaMesa? = null,
    /** A conta que este valor vai pagar. Nulo na cobrança avulsa. */
    val comandaId: String? = null,
    /** Recado curto para a tela: o que o servidor recusou, ou a rede que caiu. */
    val aviso: String? = null,
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
        get() = carrinho.sumOf { it.item.precoEmCentavos * it.quantidade }
}

/**
 * Uma linha da rodada em montagem.
 *
 * Mora só na memória da maquininha até o garçom mandar: enquanto ele escolhe,
 * nada disso existe para o resto da casa. O carrinho do banco — o que o PDV
 * mostra — só nasce quando a rodada é lançada.
 */
data class LinhaDoCarrinho(
    val item: ItemDoCardapio,
    val quantidade: Int,
    /**
     * Vale para a linha inteira, não por unidade: "sem cebola" é do prato, e
     * o que muda de um para o outro na mesma mesa — o ponto da carne — tem
     * campo próprio no sistema. Para duas observações diferentes do mesmo
     * item, o garçom lança em rodadas separadas.
     */
    val observacao: String? = null,
)

class ModeloDaTela(aplicacao: Application) : AndroidViewModel(aplicacao) {

    private val contexto = aplicacao.applicationContext
    private val configuracao = RepositorioDeConfiguracao(contexto)
    private val api = Api(configuracao)
    private val envio = EnvioTeimoso(contexto, api)
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
            _painel.update {
                it.copy(
                    mesas = resposta.mesas,
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

    /** Abre a conta de uma mesa: itens, o que já foi pago e o que falta. */
    fun abrirConta(comandaId: String) {
        viewModelScope.launch {
            _painel.update { it.copy(estado = EstadoDaTela.Conta, buscando = true, conta = null, aviso = null) }
            try {
                val conta = api.buscarConta(comandaId)
                _painel.update { it.copy(conta = conta, comandaId = comandaId, buscando = false) }
            } catch (e: Exception) {
                _painel.update {
                    it.copy(estado = EstadoDaTela.Mesas, buscando = false, aviso = e.message)
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

    /** Mais um deste item na rodada. */
    fun porNoCarrinho(item: ItemDoCardapio) {
        if (item.esgotado) return
        _painel.update { painel ->
            val existente = painel.carrinho.find { it.item.cardapioItemId == item.cardapioItemId }
            val carrinho = if (existente == null) {
                painel.carrinho + LinhaDoCarrinho(item, 1)
            } else {
                painel.carrinho.map {
                    if (it.item.cardapioItemId == item.cardapioItemId) {
                        it.copy(quantidade = it.quantidade + 1)
                    } else {
                        it
                    }
                }
            }
            painel.copy(carrinho = carrinho, aviso = null)
        }
    }

    /**
     * A observação da linha — o que vai escrito no papel da cozinha.
     *
     * Em branco, some: observação vazia gravada vira linha que não agrupa com
     * nenhuma outra, e o garçom não entende por que dois chopes aparecem
     * separados na conta.
     */
    fun anotarObservacao(item: ItemDoCardapio, texto: String) {
        val limpo = texto.trim().take(200).ifBlank { null }
        _painel.update { painel ->
            painel.copy(
                carrinho = painel.carrinho.map {
                    if (it.item.cardapioItemId == item.cardapioItemId) {
                        it.copy(observacao = limpo)
                    } else {
                        it
                    }
                }
            )
        }
    }

    /** Menos um. Chegando a zero, a linha sai da rodada. */
    fun tirarDoCarrinho(item: ItemDoCardapio) {
        _painel.update { painel ->
            val carrinho = painel.carrinho
                .map {
                    if (it.item.cardapioItemId == item.cardapioItemId) {
                        it.copy(quantidade = it.quantidade - 1)
                    } else {
                        it
                    }
                }
                .filter { it.quantidade > 0 }
            painel.copy(carrinho = carrinho)
        }
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
                        itens = painel.carrinho.map {
                            ItemParaLancar(
                                cardapioItemId = it.item.cardapioItemId,
                                quantidade = it.quantidade,
                                observacao = it.observacao,
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
        if (_painel.value.estado is EstadoDaTela.Cobrando) return
        _painel.update {
            // Entrada em centavos, da direita para a esquerda, como toda
            // maquininha: quem digita 1 5 0 0 quer R$ 15,00.
            val novo = it.valorEmCentavos * 10 + (digito - '0')
            if (novo > 99_999_999L) it else it.copy(valorEmCentavos = novo)
        }
    }

    fun apagar() {
        _painel.update { it.copy(valorEmCentavos = it.valorEmCentavos / 10) }
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

        return try {
            val resposta = api.enviarPagamento(pagamento)
            Contado(enviado = true, quitou = resposta.quitada, falta = resposta.faltaEmCentavos)
        } catch (e: Exception) {
            // Não deu agora: fica na fila em disco e sai quando a rede voltar.
            // A tela avisa, porque a conta continua aberta no salão até lá.
            Contado(enviado = envio.enviar(pagamento))
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
