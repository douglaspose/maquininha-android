package br.com.gestaorestaurante.maquininha.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.gestaorestaurante.maquininha.api.ItemDoCardapio
import br.com.gestaorestaurante.maquininha.api.MesaLivre
import br.com.gestaorestaurante.maquininha.api.OpcionalDoCardapio
import br.com.gestaorestaurante.maquininha.pagamento.FormaDePagamento
import br.com.gestaorestaurante.maquininha.pagamento.ResultadoDaCobranca

/**
 * A tela da maquininha.
 *
 * Duas regras mandam no layout: o alvo do dedo é grande porque quem opera está
 * de pé e com pressa, e o valor é a maior coisa da tela porque é a única que o
 * cliente do outro lado do balcão confere.
 */
@Composable
fun Tela(painel: Painel, acoes: Acoes) {
    when (painel.estado) {
        is EstadoDaTela.Abrindo -> TelaDeAndamento("Abrindo")
        is EstadoDaTela.Configurando -> TelaDeConfiguracao(painel, acoes)
        is EstadoDaTela.PedindoPin -> TelaDePin(painel, acoes)
        is EstadoDaTela.Mesas -> TelaDeMesas(painel, acoes)
        is EstadoDaTela.Conta -> TelaDaConta(painel, acoes)
        is EstadoDaTela.Cardapio -> TelaDoCardapio(painel, acoes)
        is EstadoDaTela.EscolhendoMesa -> TelaDeEscolhaDaMesa(painel, acoes)
        is EstadoDaTela.AbrindoMesa -> TelaDeAberturaDaMesa(painel, acoes)
        is EstadoDaTela.Cobrando ->
            TelaDeAndamento(painel.estado.mensagem, "Siga as instruções no visor")
        is EstadoDaTela.Terminada -> TelaDeResultado(painel, painel.estado, acoes)
        is EstadoDaTela.Parada -> TelaDeCobranca(painel, acoes)
    }
}

/** O que a tela sabe pedir. Um lugar só, para a tela não conhecer o modelo. */
class Acoes(
    val digitar: (Char) -> Unit,
    val apagar: () -> Unit,
    val limpar: () -> Unit,
    val escolherForma: (FormaDePagamento) -> Unit,
    val escolherParcelas: (Int) -> Unit,
    val cobrar: () -> Unit,
    val configurar: (String, String) -> Unit,
    val abrirConfiguracao: () -> Unit,
    val entrarComPin: (String) -> Unit,
    val sairDoTurno: () -> Unit,
    val atualizarMesas: () -> Unit,
    val irParaAbertura: () -> Unit,
    val escolherMesaLivre: (MesaLivre) -> Unit,
    /** Pessoas e nome do cliente; o nome vai vazio onde a casa não o exige. */
    val abrirMesa: (Int, String) -> Unit,
    val abrirConta: (String) -> Unit,
    val abrirCardapio: () -> Unit,
    val pedirAConta: () -> Unit,
    val reabrirConta: () -> Unit,
    val porNoCarrinho: (ItemDoCardapio, List<OpcionalDoCardapio>) -> Unit,
    val tirarDoCarrinho: (String) -> Unit,
    val lancarRodada: () -> Unit,
    val anotarObservacao: (String, String) -> Unit,
    val cobrarDaConta: (Boolean) -> Unit,
    val cobrancaAvulsa: () -> Unit,
    val voltar: () -> Unit,
)

/**
 * As formas em duas fileiras fixas, na ordem em que um restaurante cobra.
 *
 * As cinco lado a lado não cabiam na largura da maquininha: o rótulo quebrava
 * no meio da palavra e a tela mostrava "Crédi to" e "Vouc her".
 */
private val FILEIRAS_DE_FORMAS = listOf(
    listOf(FormaDePagamento.CREDITO, FormaDePagamento.DEBITO, FormaDePagamento.PIX),
    listOf(FormaDePagamento.CREDITO_PARCELADO, FormaDePagamento.VOUCHER),
)

@Composable
private fun TelaDeCobranca(painel: Painel, acoes: Acoes) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Vindo de uma mesa, o cabeçalho diz de qual conta é este valor e como
        // voltar para ela; na cobrança avulsa, continua o estado do aparelho.
        if (painel.comandaId != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = acoes.voltar) { Text("← Conta") }
                Spacer(Modifier.weight(1f))
                Text(
                    painel.conta?.let { it.mesa ?: "Comanda ${it.numero}" }.orEmpty(),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        } else {
            Cabecalho(painel, acoes)
        }
        Visor(painel)

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (fileira in FILEIRAS_DE_FORMAS) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (forma in fileira) {
                        Forma(
                            rotulo = forma.curto,
                            escolhida = painel.forma == forma,
                            aoTocar = { acoes.escolherForma(forma) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // A segunda fileira tem uma forma a menos que a primeira; o
                    // vão guarda o lugar para os chips ficarem da mesma largura
                    // nas duas, em vez de esticarem só nesta.
                    repeat(FILEIRAS_DE_FORMAS[0].size - fileira.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }

            if (painel.forma.parcela) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (n in listOf(2, 3, 4, 6, 10, 12)) {
                        /*
                         * Botão próprio, e não o chip das formas: seis numa
                         * fileira, a folga interna do chip comia o rótulo e
                         * "10x" e "12x" apareciam como "10" e "12" — que é
                         * exatamente o que ninguém pode ler errado aqui.
                         */
                        Tecla(
                            rotulo = "${n}x",
                            cor = if (painel.parcelas == n) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            corDoTexto = if (painel.parcelas == n) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            tamanhoDoTexto = 14.sp,
                            aoTocar = { acoes.escolherParcelas(n) },
                            modifier = Modifier.weight(1f).height(40.dp),
                        )
                    }
                }
            }
        }

        Teclado(
            modifier = Modifier.weight(1f),
            aoDigitar = acoes.digitar,
            aoApagar = acoes.apagar,
            aoLimpar = acoes.limpar,
        )

        if (!painel.provedorPronto) {
            Text(
                painel.motivoDoProvedor.ifBlank { "Maquininha não está pronta para cobrar." },
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Button(
            onClick = acoes.cobrar,
            enabled = painel.valorEmCentavos > 0 && painel.provedorPronto,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth().height(64.dp),
        ) {
            // O valor vai no botão: é o último lugar onde dá para perceber um
            // dígito a mais antes de a maquininha pedir o cartão.
            Text(
                if (painel.valorEmCentavos > 0) {
                    "Cobrar ${emReais(painel.valorEmCentavos)}"
                } else {
                    "Cobrar"
                },
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * O visor do valor: a maior coisa da tela, porque é a única que o cliente do
 * outro lado do balcão confere.
 */
@Composable
private fun Visor(painel: Painel) {
    val vazio = painel.valorEmCentavos == 0L
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Text(
                painel.forma.curto.uppercase(),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = emReais(painel.valorEmCentavos),
                fontSize = 46.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.End,
                maxLines = 1,
                // Apagado enquanto está zerado: o zero é o campo esperando,
                // não um valor a cobrar.
                color = if (vazio) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (painel.forma.parcela && painel.parcelas > 1 && !vazio) {
                Text(
                    "${painel.parcelas}x de ${emReais(painel.valorEmCentavos / painel.parcelas)}",
                    fontSize = 13.sp,
                    textAlign = TextAlign.End,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Forma de pagamento e número de parcelas usam o mesmo botão de escolha. */
@Composable
private fun Forma(
    rotulo: String,
    escolhida: Boolean,
    aoTocar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = escolhida,
        onClick = aoTocar,
        shape = RoundedCornerShape(12.dp),
        label = {
            Text(
                rotulo,
                fontSize = 13.sp,
                fontWeight = if (escolhida) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        modifier = modifier.height(44.dp),
    )
}

@Composable
private fun Cabecalho(painel: Painel, acoes: Acoes) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = painel.configuracao.unidade.ifBlank { "Sem unidade" },
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            /*
             * O estado vira sinal de cor, e não mais uma frase.
             *
             * Quem está atendendo não lê "Simulado · pronta" antes de cada
             * venda; o que precisa saltar é o contrário — a maquininha que
             * não vai cobrar, e o resultado que o servidor ainda não recebeu.
             */
            val (cor, texto) = when {
                !painel.provedorPronto ->
                    MaterialTheme.colorScheme.error to "${painel.credenciadora} · não pronta"
                painel.pendentesDeEnvio > 0 ->
                    MaterialTheme.colorScheme.tertiary to
                        "${painel.pendentesDeEnvio} venda(s) por enviar"
                else ->
                    MaterialTheme.colorScheme.primary to "${painel.credenciadora} · pronta"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .padding(end = 6.dp)
                        .size(8.dp)
                        .background(cor, CircleShape),
                )
                Text(texto, fontSize = 12.sp, color = cor, maxLines = 1)
            }
        }
        TextButton(onClick = acoes.abrirConfiguracao) { Text("Ajustes") }
    }
}

/**
 * Teclado próprio, e não o do sistema: numa maquininha o teclado do Android
 * cobre metade da tela e some com o valor que o cliente precisa ver.
 */
@Composable
internal fun Teclado(
    modifier: Modifier = Modifier,
    aoDigitar: (Char) -> Unit,
    aoApagar: () -> Unit,
    aoLimpar: () -> Unit,
) {
    val linhas = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("C", "0", "⌫"),
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (linha in linhas) {
            Row(
                Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (tecla in linha) {
                    val numero = tecla.first().isDigit()
                    Tecla(
                        rotulo = tecla,
                        // A tecla que apaga tudo não pode parecer com as de
                        // digitar: quem erra de dedo apaga o valor inteiro.
                        cor = if (numero) {
                            MaterialTheme.colorScheme.surface
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        corDoTexto = when {
                            numero -> MaterialTheme.colorScheme.onSurface
                            tecla == "C" -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        aoTocar = {
                            when (tecla) {
                                "C" -> aoLimpar()
                                "⌫" -> aoApagar()
                                else -> aoDigitar(tecla[0])
                            }
                        },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

/** Uma tecla: retângulo arrredondado inteiro, porque o dedo mira a área toda. */
@Composable
internal fun Tecla(
    rotulo: String,
    cor: Color,
    corDoTexto: Color,
    aoTocar: () -> Unit,
    modifier: Modifier = Modifier,
    tamanhoDoTexto: TextUnit = 26.sp,
) {
    Surface(
        onClick = aoTocar,
        color = cor,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                rotulo,
                fontSize = tamanhoDoTexto,
                fontWeight = FontWeight.Medium,
                color = corDoTexto,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TelaDeAndamento(mensagem: String, dica: String? = null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(24.dp))
            Text(mensagem, fontSize = 20.sp, textAlign = TextAlign.Center)
            if (dica != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    dica,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TelaDeResultado(painel: Painel, estado: EstadoDaTela.Terminada, acoes: Acoes) {
    val (titulo, detalhe) = when (val r = estado.resultado) {
        is ResultadoDaCobranca.Aprovada ->
            "Aprovada" to buildString {
                append(emReais(r.transacao.valorEmCentavos))
                r.transacao.bandeira?.let { append(" · $it") }
                r.transacao.finalDoCartao?.let { append(" · final $it") }
                r.transacao.autorizacao?.let { append("\nAutorização $it") }
            }
        is ResultadoDaCobranca.Recusada -> "Recusada" to r.motivo
        is ResultadoDaCobranca.Cancelada -> "Cancelada" to "Ninguém concluiu no visor."
        is ResultadoDaCobranca.Erro -> "Não deu" to r.motivo
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(titulo, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(detalhe, fontSize = 15.sp, textAlign = TextAlign.Center)

        /*
         * O que aconteceu com a conta, quando o pagamento era de uma mesa.
         * Quitada, ela já fechou no salão e o cupom saiu; faltando, ela
         * continua aberta — e quem está no balcão precisa saber disso antes de
         * liberar a mesa.
         */
        if (painel.comandaId != null && estado.resultado is ResultadoDaCobranca.Aprovada && estado.enviado) {
            Spacer(Modifier.height(12.dp))
            Text(
                if (estado.quitou) {
                    "Conta quitada e fechada."
                } else {
                    "Falta ${emReais(estado.faltaEmCentavos)} nesta conta."
                },
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = if (estado.quitou) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        if (!estado.enviado) {
            Spacer(Modifier.height(16.dp))
            Card {
                Text(
                    /*
                     * Isto precisa aparecer para o caixa. A transação existe
                     * na credenciadora e o servidor ainda não sabe — a conta
                     * no salão continua aberta até a maquininha conseguir
                     * contar. Quem vir isto e fechar a conta na mão vai
                     * duplicar o pagamento quando a rede voltar.
                     */
                    text = "O servidor ainda não recebeu este resultado. " +
                        "Ele será enviado sozinho quando a rede voltar — " +
                        "não lance esta venda de novo.",
                    modifier = Modifier.padding(12.dp),
                    fontSize = 13.sp,
                )
            }
        }

        Spacer(Modifier.height(32.dp))
        Button(
            onClick = acoes.limpar,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Text("Nova cobrança", fontSize = 18.sp)
        }
    }
}

@Composable
private fun TelaDeConfiguracao(painel: Painel, acoes: Acoes) {
    var servidor by remember { mutableStateOf(painel.configuracao.servidor) }
    var token by remember { mutableStateOf(painel.configuracao.token) }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Ajustes do aparelho", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "O token é o mesmo da unidade, o que o agente de impressão usa. " +
                "Ele diz de qual restaurante esta maquininha é.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = servidor,
            onValueChange = { servidor = it },
            label = { Text("Endereço do servidor") },
            placeholder = { Text("https://gestao.exemplo.com.br") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Token da unidade") },
            singleLine = true,
            // Monoespaçada: é um token que alguém vai conferir caractere a
            // caractere quando o servidor responder 401.
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = { acoes.configurar(servidor, token) },
            enabled = servidor.isNotBlank() && token.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text("Salvar")
        }

        if (painel.configuracao.completa) {
            TextButton(onClick = acoes.limpar, modifier = Modifier.fillMaxWidth()) {
                Text("Voltar")
            }
        }
    }
}

private val FormaDePagamento.curto: String
    get() = when (this) {
        FormaDePagamento.CREDITO -> "Crédito"
        FormaDePagamento.CREDITO_PARCELADO -> "Parcelado"
        FormaDePagamento.DEBITO -> "Débito"
        FormaDePagamento.PIX -> "Pix"
        FormaDePagamento.VOUCHER -> "Voucher"
    }
