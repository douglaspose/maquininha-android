package br.com.gestaorestaurante.maquininha.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        is EstadoDaTela.Configurando -> TelaDeConfiguracao(painel, acoes)
        is EstadoDaTela.Cobrando -> TelaDeAndamento(painel.estado.mensagem)
        is EstadoDaTela.Terminada -> TelaDeResultado(painel.estado, acoes)
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
)

@Composable
private fun TelaDeCobranca(painel: Painel, acoes: Acoes) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Cabecalho(painel, acoes)

        Text(
            text = emReais(painel.valorEmCentavos),
            fontSize = 44.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        )

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (forma in FormaDePagamento.entries) {
                FilterChip(
                    selected = painel.forma == forma,
                    onClick = { acoes.escolherForma(forma) },
                    label = { Text(forma.curto, fontSize = 11.sp) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (painel.forma.parcela) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (n in listOf(2, 3, 4, 6, 10, 12)) {
                    FilterChip(
                        selected = painel.parcelas == n,
                        onClick = { acoes.escolherParcelas(n) },
                        label = { Text("${n}x", fontSize = 11.sp) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        Teclado(
            modifier = Modifier.weight(1f).padding(top = 12.dp),
            aoDigitar = acoes.digitar,
            aoApagar = acoes.apagar,
            aoLimpar = acoes.limpar,
        )

        Button(
            onClick = acoes.cobrar,
            enabled = painel.valorEmCentavos > 0 && painel.provedorPronto,
            modifier = Modifier.fillMaxWidth().height(60.dp),
        ) {
            Text("Cobrar", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
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
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = buildString {
                    append(painel.credenciadora)
                    append(if (painel.provedorPronto) " · pronta" else " · ")
                    if (!painel.provedorPronto) append(painel.motivoDoProvedor)
                    if (painel.pendentesDeEnvio > 0) {
                        append(" · ${painel.pendentesDeEnvio} por enviar")
                    }
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = acoes.abrirConfiguracao) { Text("Ajustes") }
    }
}

/**
 * Teclado próprio, e não o do sistema: numa maquininha o teclado do Android
 * cobre metade da tela e some com o valor que o cliente precisa ver.
 */
@Composable
private fun Teclado(
    modifier: Modifier = Modifier,
    aoDigitar: (Char) -> Unit,
    aoApagar: () -> Unit,
    aoLimpar: () -> Unit,
) {
    val linhas = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("C", "0", "<"),
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (linha in linhas) {
            Row(
                Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (tecla in linha) {
                    OutlinedButton(
                        onClick = {
                            when (tecla) {
                                "C" -> aoLimpar()
                                "<" -> aoApagar()
                                else -> aoDigitar(tecla[0])
                            }
                        },
                        modifier = Modifier.weight(1f).fillMaxSize(),
                    ) {
                        Text(tecla, fontSize = 22.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun TelaDeAndamento(mensagem: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(24.dp))
            Text(mensagem, fontSize = 20.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                "Siga as instruções no visor",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TelaDeResultado(estado: EstadoDaTela.Terminada, acoes: Acoes) {
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
