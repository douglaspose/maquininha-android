package br.com.gestaorestaurante.maquininha.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Tirar um item da conta.
 *
 * "Esse eu não quero mais", "veio errado", "a cozinha não tem": quem ouve é o
 * garçom que está na mesa, e a cozinha precisa saber agora. Até aqui ele tinha
 * de ir ao tablet do balcão — e, se o cargo dele não alcançasse, procurar o
 * gerente e esperar os dois voltarem até lá.
 *
 * O motivo é obrigatório porque é a única coisa que responde, semanas depois,
 * por que aquele prato saiu da conta. Os motivos prontos existem porque este
 * teclado é o pior do restaurante: sem eles, "cliente desistiu" vira "cd" — e
 * um motivo que ninguém entende é o mesmo que motivo nenhum.
 */

/** Os quatro que respondem quase todo cancelamento de salão. */
private val MOTIVOS_PRONTOS = listOf(
    "Cliente desistiu",
    "Veio errado",
    "Cozinha sem o item",
    "Demorou demais",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TelaDeCancelamento(painel: Painel, acoes: Acoes) {
    val item = painel.itemParaCancelar
    // Sem item não há o que cancelar: a conta é a tela que faz sentido.
    if (item == null) {
        TelaDaConta(painel, acoes)
        return
    }

    var motivo by remember(item.itemId) { mutableStateOf("") }
    var pin by remember(item.itemId) { mutableStateOf("") }

    // PIN recusado limpa o campo, para o gerente redigitar sem apagar à mão.
    LaunchedEffect(painel.aviso) { if (painel.aviso != null) pin = "" }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = acoes.voltar) { Text("← Conta") }
            Spacer(Modifier.weight(1f))
            Text("Cancelar item", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }

        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text(
                    "${quantidade(item.quantidade)}x ${item.titulo}",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                )
                Text(emReais(item.totalEmCentavos), fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
        }

        painel.aviso?.let { Aviso(it, MaterialTheme.colorScheme.error) }

        if (painel.pedindoLiberacao) {
            /*
             * O gerente digita aqui, no aparelho que está na mão do garçom. O
             * motivo escrito continua valendo: quem libera não reescreve o que
             * o garçom já explicou.
             */
            Text(
                "Peça o PIN de quem pode cancelar",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Bolinhas: o PIN do gerente é digitado na frente do cliente.
                Text(
                    text = if (pin.isEmpty()) "—" else "•".repeat(pin.length),
                    fontSize = 32.sp,
                    letterSpacing = 8.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                )
            }

            Teclado(
                modifier = Modifier.weight(1f),
                aoDigitar = { if (pin.length < 8) pin += it },
                aoApagar = { pin = pin.dropLast(1) },
                aoLimpar = { pin = "" },
            )

            Button(
                onClick = { acoes.liberarCancelamento(pin, motivo) },
                enabled = pin.length >= 4 && !painel.buscando,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Text(
                    if (painel.buscando) "Liberando..." else "Liberar e cancelar",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        } else {
            Text(
                "Por que está saindo da conta?",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (pronto in MOTIVOS_PRONTOS) {
                    FilterChip(
                        selected = motivo == pronto,
                        onClick = { motivo = if (motivo == pronto) "" else pronto },
                        label = { Text(pronto, fontSize = 13.sp) },
                    )
                }
            }

            OutlinedTextField(
                value = motivo,
                onValueChange = { motivo = it },
                label = { Text("Motivo") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.weight(1f))

            Button(
                onClick = { acoes.cancelarItem(motivo, null) },
                enabled = motivo.isNotBlank() && !painel.buscando,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Text(
                    when {
                        painel.buscando -> "Cancelando..."
                        // O botão diz o que falta em vez de ficar apagado sem
                        // explicação, como o das escolhas do cardápio.
                        motivo.isBlank() -> "Escolha o motivo"
                        else -> "Cancelar o item"
                    },
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
