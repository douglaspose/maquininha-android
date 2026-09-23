package br.com.gestaorestaurante.maquininha.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.gestaorestaurante.maquininha.api.MesaAberta

/**
 * O salão dentro da maquininha: o PIN, as mesas abertas e a conta.
 *
 * Fica ao lado da tela de cobrança, e não dentro dela, porque é outra
 * conversa: ali se cobra um valor, aqui se fecha a conta de uma mesa — e é
 * daqui que o valor sai, já conferido pelo servidor.
 */

/**
 * Começo de turno: quem vai receber digita o PIN.
 *
 * O mesmo PIN do salão, e o mesmo teclado da cobrança — quem opera a
 * maquininha já sabe onde ficam os números. O PIN não fica guardado no
 * aparelho: o que fica é o turno, que vence em doze horas.
 */
@Composable
internal fun TelaDePin(painel: Painel, acoes: Acoes) {
    var pin by remember { mutableStateOf("") }

    // PIN recusado limpa o campo, para o operador redigitar sem apagar à mão.
    LaunchedEffect(painel.falhasNoPin) {
        if (painel.falhasNoPin > 0) pin = ""
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    painel.configuracao.unidade.ifBlank { "Maquininha" },
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text(
                    "Digite seu PIN para começar o turno",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = acoes.abrirConfiguracao) { Text("Ajustes") }
        }

        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Bolinhas, e não os números: o cliente está do outro lado do balcão.
            Text(
                text = if (pin.isEmpty()) "—" else "•".repeat(pin.length),
                fontSize = 36.sp,
                letterSpacing = 8.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 22.dp),
            )
        }

        painel.aviso?.let { Aviso(it, MaterialTheme.colorScheme.error) }

        Teclado(
            modifier = Modifier.weight(1f),
            aoDigitar = { if (pin.length < 8) pin += it },
            aoApagar = { pin = pin.dropLast(1) },
            aoLimpar = { pin = "" },
        )

        Button(
            onClick = { acoes.entrarComPin(pin) },
            enabled = pin.length >= 4 && !painel.buscando,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth().height(64.dp),
        ) {
            Text(
                if (painel.buscando) "Entrando..." else "Entrar",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * O salão: as contas abertas, com o que falta em cada uma.
 *
 * É onde a maquininha fica parada entre uma venda e outra, então o que importa
 * é achar a mesa de relance — o nome grande à esquerda e o que falta à
 * direita, que é o valor que vai ser cobrado.
 */
@Composable
internal fun TelaDeMesas(painel: Painel, acoes: Acoes) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    painel.configuracao.unidade.ifBlank { "Mesas abertas" },
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text(
                    buildString {
                        append(painel.configuracao.operadorNome.ifBlank { "Operador" })
                        append(" · ${painel.mesas.size} mesa(s)")
                        if (painel.pendentesDeEnvio > 0) {
                            append(" · ${painel.pendentesDeEnvio} por enviar")
                        }
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            TextButton(onClick = acoes.atualizarMesas, enabled = !painel.buscando) {
                Text(if (painel.buscando) "..." else "Atualizar")
            }
            TextButton(onClick = acoes.sairDoTurno) { Text("Sair") }
        }

        if (!painel.caixaAberto) {
            Aviso(
                "Nenhum caixa aberto: o pagamento não entra. Abra o caixa no sistema antes de cobrar.",
                MaterialTheme.colorScheme.error,
            )
        }
        painel.aviso?.let { Aviso(it, MaterialTheme.colorScheme.error) }

        if (painel.mesas.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    if (painel.buscando) "Buscando as mesas..." else "Nenhuma conta aberta agora.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(painel.mesas, key = { it.comandaId }) { mesa ->
                    MesaNaLista(mesa) { acoes.abrirConta(mesa.comandaId) }
                }
            }
        }

        // A cobrança avulsa continua existindo: é a venda de balcão que não
        // passou por comanda nenhuma.
        OutlinedButton(
            onClick = acoes.cobrancaAvulsa,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text("Cobrar um valor avulso", fontSize = 16.sp)
        }
    }
}

@Composable
private fun MesaNaLista(mesa: MesaAberta, aoTocar: () -> Unit) {
    Surface(
        onClick = aoTocar,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    mesa.mesa ?: mesa.nomeCliente ?: "Comanda ${mesa.numero}",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text(
                    buildString {
                        append("#${mesa.numero} · ${mesa.pessoas} pessoa(s)")
                        /*
                         * Conta já pedida é onde o caixa costuma estar: separa
                         * quem está esperando a maquininha de quem ainda está
                         * comendo.
                         */
                        if (mesa.fechando) append(" · conta pedida")
                        if (mesa.recebidoEmCentavos > 0) {
                            append(" · pago ${emReais(mesa.recebidoEmCentavos)}")
                        }
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            Text(
                emReais(mesa.faltaEmCentavos),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = if (mesa.fechando) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

/** A conta de uma mesa: o que foi consumido, o que já foi pago e o que falta. */
@Composable
internal fun TelaDaConta(painel: Painel, acoes: Acoes) {
    val conta = painel.conta

    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = acoes.voltar) { Text("← Mesas") }
            Spacer(Modifier.weight(1f))
            Text(
                conta?.let { it.mesa ?: "Comanda ${it.numero}" }.orEmpty(),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        if (conta == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }

        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            LazyColumn(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                items(conta.itens) { item ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(
                            "${quantidade(item.quantidade)}x ${item.titulo}",
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f),
                        )
                        Text(emReais(item.totalEmCentavos), fontSize = 14.sp)
                    }
                }
                item {
                    Column(Modifier.padding(top = 10.dp)) {
                        LinhaDaConta("Subtotal", conta.subtotalEmCentavos)
                        if (conta.descontoEmCentavos > 0) {
                            LinhaDaConta("Desconto", -conta.descontoEmCentavos)
                        }
                        if (conta.taxaServicoEmCentavos > 0) {
                            LinhaDaConta("Taxa de serviço", conta.taxaServicoEmCentavos)
                        }
                        LinhaDaConta("Total", conta.totalEmCentavos, destaque = true)
                        conta.porPessoaEmCentavos?.let {
                            LinhaDaConta("Por pessoa (${conta.pessoas})", it)
                        }
                        for (pago in conta.pagamentos) {
                            LinhaDaConta("Pago · ${pago.forma}", -pago.valorEmCentavos)
                        }
                    }
                }
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Falta pagar",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    emReais(conta.faltaEmCentavos),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        Button(
            onClick = { acoes.cobrarDaConta(true) },
            enabled = conta.faltaEmCentavos > 0 && painel.caixaAberto,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth().height(60.dp),
        ) {
            Text(
                "Cobrar ${emReais(conta.faltaEmCentavos)}",
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        /*
         * Quem paga a parte dele e vai embora: o teclado abre zerado, e a
         * conta continua aberta com o resto — foi a decisão do dono sobre
         * pagamento parcial na maquininha.
         */
        OutlinedButton(
            onClick = { acoes.cobrarDaConta(false) },
            enabled = conta.faltaEmCentavos > 0 && painel.caixaAberto,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text("Cobrar outro valor", fontSize = 16.sp)
        }
    }
}

@Composable
private fun LinhaDaConta(rotulo: String, centavos: Long, destaque: Boolean = false) {
    val cor = if (destaque) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(
            rotulo,
            fontSize = if (destaque) 16.sp else 13.sp,
            fontWeight = if (destaque) FontWeight.Bold else FontWeight.Normal,
            color = cor,
            modifier = Modifier.weight(1f),
        )
        Text(
            emReais(centavos),
            fontSize = if (destaque) 16.sp else 13.sp,
            fontWeight = if (destaque) FontWeight.Bold else FontWeight.Normal,
            color = cor,
        )
    }
}

@Composable
internal fun Aviso(texto: String, cor: Color) {
    Surface(
        color = cor.copy(alpha = 0.10f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(texto, fontSize = 13.sp, color = cor, modifier = Modifier.padding(12.dp))
    }
}

/** 1.0 vira "1"; 0,5 continua "0,5" — a cozinha lança meia porção. */
private fun quantidade(q: Double): String =
    if (q == q.toLong().toDouble()) q.toLong().toString() else q.toString().replace('.', ',')
