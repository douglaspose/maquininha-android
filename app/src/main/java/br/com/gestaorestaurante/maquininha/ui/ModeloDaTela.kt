package br.com.gestaorestaurante.maquininha.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import br.com.gestaorestaurante.maquininha.PlataformaAtual
import br.com.gestaorestaurante.maquininha.api.Api
import br.com.gestaorestaurante.maquininha.api.ResultadoDaCobrancaEnviado
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
    data object Configurando : EstadoDaTela
    data object Parada : EstadoDaTela
    data class Cobrando(val mensagem: String) : EstadoDaTela
    data class Terminada(val resultado: ResultadoDaCobranca, val enviado: Boolean) : EstadoDaTela
}

data class Painel(
    val configuracao: Configuracao = Configuracao(),
    val credenciadora: String = "",
    val provedorPronto: Boolean = false,
    val motivoDoProvedor: String = "",
    val estado: EstadoDaTela = EstadoDaTela.Parada,
    val valorEmCentavos: Long = 0,
    val forma: FormaDePagamento = FormaDePagamento.CREDITO,
    val parcelas: Int = 1,
    val pendentesDeEnvio: Int = 0,
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
            _painel.update {
                it.copy(
                    configuracao = config,
                    estado = if (config.completa) EstadoDaTela.Parada else EstadoDaTela.Configurando,
                )
            }
            if (config.completa) {
                ServicoDaFila.ligar(contexto)
                // Resultado que ficou preso numa queda de rede sai agora, antes
                // de qualquer venda nova.
                envio.escoar()
                _painel.update { it.copy(pendentesDeEnvio = envio.quantasPendentes()) }
            }
            prepararProvedor()
        }
        viewModelScope.launch {
            configuracao.fluxo.collect { config ->
                _painel.update { it.copy(configuracao = config) }
            }
        }
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

    fun limpar() {
        _painel.update { it.copy(valorEmCentavos = 0, estado = EstadoDaTela.Parada) }
    }

    fun escolherForma(forma: FormaDePagamento) {
        _painel.update { it.copy(forma = forma, parcelas = if (forma.parcela) it.parcelas else 1) }
    }

    fun escolherParcelas(parcelas: Int) {
        _painel.update { it.copy(parcelas = parcelas.coerceIn(1, 12)) }
    }

    fun configurar(servidor: String, token: String) {
        viewModelScope.launch {
            configuracao.guardar(servidor, token)
            val config = configuracao.atual()
            _painel.update {
                it.copy(
                    configuracao = config,
                    estado = if (config.completa) EstadoDaTela.Parada else EstadoDaTela.Configurando,
                )
            }
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
             * A referencia nasce aqui e vai junto para a credenciadora. Quando
             * a rota de cobrancas do servidor existir, ela sera o id da
             * cobranca de la; enquanto o valor e digitado na maquininha, um
             * UUID ja serve para achar a transacao na conciliacao.
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

            val enviado = contar(resultado, cobranca)
            if (resultado is ResultadoDaCobranca.Aprovada) imprimirComprovante(resultado.transacao)

            _painel.update {
                it.copy(
                    estado = EstadoDaTela.Terminada(resultado, enviado),
                    pendentesDeEnvio = envio.quantasPendentes(),
                )
            }
        }
    }

    /** Conta ao servidor o que aconteceu, e insiste se precisar. */
    private suspend fun contar(resultado: ResultadoDaCobranca, cobranca: Cobranca): Boolean {
        val aprovada = (resultado as? ResultadoDaCobranca.Aprovada)?.transacao
        val enviado = ResultadoDaCobrancaEnviado(
            id = cobranca.referencia,
            resultado = when (resultado) {
                is ResultadoDaCobranca.Aprovada -> "APROVADA"
                is ResultadoDaCobranca.Recusada -> "RECUSADA"
                is ResultadoDaCobranca.Cancelada -> "CANCELADA"
                is ResultadoDaCobranca.Erro -> "ERRO"
            },
            credenciadora = PlataformaAtual.credenciadora,
            identificadorNaCredenciadora = aprovada?.identificadorNaCredenciadora,
            bandeira = aprovada?.bandeira,
            autorizacao = aprovada?.autorizacao,
            finalDoCartao = aprovada?.finalDoCartao,
            parcelas = cobranca.parcelas,
            motivo = when (resultado) {
                is ResultadoDaCobranca.Recusada -> resultado.motivo
                is ResultadoDaCobranca.Erro -> resultado.motivo
                else -> null
            },
        )
        return envio.enviar(enviado)
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
