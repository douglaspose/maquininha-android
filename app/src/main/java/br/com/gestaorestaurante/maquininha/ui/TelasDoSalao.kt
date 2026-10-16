package br.com.gestaorestaurante.maquininha.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.gestaorestaurante.maquininha.api.ItemDoCardapio
import br.com.gestaorestaurante.maquininha.api.MesaAberta
import br.com.gestaorestaurante.maquininha.api.MesaLivre
import br.com.gestaorestaurante.maquininha.api.OpcionalDoCardapio

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
    /*
     * O salão se refaz sozinho enquanto está na tela.
     *
     * Sem isto, o chamado que o cliente faz pelo QR só aparecia quando alguém
     * tocasse em "Atualizar" — e quem está de mão levantada não sabe que
     * precisa que o garçom toque num botão. Meio minuto é o intervalo que
     * mantém a espera curta sem conversar com o servidor à toa: o aparelho
     * fica nesta tela a noite inteira.
     */
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            acoes.atualizarMesas()
        }
    }

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

        // O caixa fechado é problema de quem vai cobrar; para o garçom, é um
        // alarme vermelho sobre algo que ele não resolve nem precisa.
        if (!painel.caixaAberto && painel.podeReceber) {
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

        /*
         * Abrir mesa fica aqui, na tela de quem chega: o garçom que recebe uma
         * mesa nova tinha de voltar ao tablet do balcão só para abri-la, e só
         * então podia lançar o pedido pelo aparelho.
         *
         * Preenchido, e a cobrança avulsa contornada: no turno do garçom esta é
         * a ação do começo do atendimento, e a avulsa é do caixa.
         */
        if (painel.podeAbrir) {
            Button(
                onClick = acoes.irParaAbertura,
                enabled = painel.livres.isNotEmpty(),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(
                    if (painel.livres.isEmpty()) {
                        "Nenhuma mesa livre"
                    } else {
                        "Abrir mesa (${painel.livres.size} livre${if (painel.livres.size > 1) "s" else ""})"
                    },
                    fontSize = 16.sp,
                )
            }
        }

        // A cobrança avulsa continua existindo: é a venda de balcão que não
        // passou por comanda nenhuma — e é do caixa, não do garçom.
        if (painel.podeReceber) {
            OutlinedButton(
                onClick = acoes.cobrancaAvulsa,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text("Cobrar um valor avulso", fontSize = 16.sp)
            }
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
        /*
         * Alinhado pelo topo, e não pelo meio: o valor pertence ao nome da
         * mesa e fica na linha dele. Centralizado, ele flutuava no meio de um
         * subtítulo de duas linhas, longe do que nomeia.
         */
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                /*
                 * A mão levantada vem antes do nome da mesa, e colorida: é a
                 * única linha do salão que pede uma decisão agora. Com a
                 * espera escrita, porque é por ela que o garçom escolhe entre
                 * duas mesas chamando.
                 */
                mesa.chamadoEm?.let { desde ->
                    Text(
                        listOfNotNull("CHAMANDO", esperaDesde(desde)).joinToString(" · "),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
                Text(
                    mesa.mesa ?: mesa.nomeCliente ?: "Comanda ${mesa.numero}",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                /*
                 * O que já entrou fica numa linha só dele. Emendado no resto,
                 * quebrava no meio — "… · pago" numa linha e "R$ 45,87" na
                 * outra —, que é justamente o número que o caixa procura.
                 */
                if (mesa.recebidoEmCentavos > 0) {
                    Text(
                        "pago ${emReais(mesa.recebidoEmCentavos)}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            Text(
                emReais(mesa.faltaEmCentavos),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
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
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    conta?.let { it.mesa ?: "Comanda ${it.numero}" }.orEmpty(),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                )
                /*
                 * Conta pedida precisa estar escrito, e não só implícito nos
                 * botões: é o que explica por que "Lançar itens" sumiu da tela
                 * e um "Reabrir" apareceu no lugar.
                 */
                if (conta?.status == "FECHANDO") {
                    Text(
                        "conta pedida",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
            }
        }

        if (conta == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }

        /*
         * A conta não mostrava recado nenhum: o que o servidor recusasse aqui
         * — conta já paga, rede fora — sumia, e o garçom ficava tocando num
         * botão que parecia não fazer nada.
         */
        painel.aviso?.let { Aviso(it, MaterialTheme.colorScheme.error) }
        painel.recado?.let { Aviso(it, MaterialTheme.colorScheme.primary) }

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

        /*
         * Os dois botões de cobrança só para quem recebe pagamento. Para o
         * garçom, esta tela é a conta da mesa e o caminho para lançar a
         * rodada — oferecer "Cobrar" a ele seria um caminho que termina em
         * recusa com o cartão do cliente na mão.
         */
        if (painel.podeReceber) {
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
        /*
         * Atender fica acima de tudo, e só aparece com a mesa chamando: é a
         * razão de o garçom ter aberto esta conta, e some assim que ele toca.
         */
        conta.chamadoEm?.let { desde ->
            Button(
                onClick = acoes.atenderChamado,
                enabled = !painel.buscando,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(
                    listOfNotNull("Atender chamado", esperaDesde(desde)).joinToString(" · "),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        /*
         * "A conta, por favor" dita ao garçom que está na mesa: daqui sai o
         * fechamento, que acende a mesa no mapa do salão, e a folha que o
         * cliente confere. Antes disto, era a hora de voltar ao balcão.
         */
        if (painel.podePedirConta) {
            OutlinedButton(
                onClick = acoes.pedirAConta,
                enabled = !painel.buscando,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(
                    // Pedida uma vez, o que o garçom faz de novo é tirar outra
                    // via — e é isso que o botão passa a prometer.
                    if (conta.status == "FECHANDO") "Imprimir outra via" else "Pedir a conta",
                    fontSize = 16.sp,
                )
            }
        }

        /*
         * Mudar de mesa é do atendimento, e fica com o resto dele: "dá para
         * sentar lá fora?" é dito ao garçom que está de pé nesta mesa, e a
         * conta que ele tem na mão é esta.
         */
        if (painel.podeTransferir) {
            OutlinedButton(
                onClick = acoes.irParaTransferencia,
                enabled = !painel.buscando,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text("Transferir mesa", fontSize = 16.sp)
            }
        }

        /*
         * Lançar fica junto de cobrar, e não numa tela à parte: é a mesma mesa,
         * e o garçom que acabou de conferir a conta é quem ouve "traz mais uma".
         *
         * Em fechamento o servidor recusa item novo, então no lugar de lançar
         * vem reabrir: deixar "Lançar itens" ali seria mandar o garçom montar
         * uma rodada inteira para ouvir "a conta está em fechamento" no fim.
         */
        if (conta.status == "FECHANDO") {
            OutlinedButton(
                onClick = acoes.reabrirConta,
                enabled = !painel.buscando,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text("Reabrir para lançar", fontSize = 16.sp)
            }
        } else {
            OutlinedButton(
                onClick = acoes.abrirCardapio,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text("Lançar itens", fontSize = 16.sp)
            }
        }
    }
}

/**
 * O cardápio, para o garçom montar a rodada na mesa.
 *
 * A rodada inteira antes de mandar: o garçom ouve a mesa toda, confere e
 * envia. Item a item, cada toque seria uma ida à cozinha — e uma chance de a
 * rede falhar no meio.
 */
@Composable
internal fun TelaDoCardapio(painel: Painel, acoes: Acoes) {
    // Qual linha está com a observação aberta, e qual item está escolhendo
    // opcionais. Nulos: nenhum dos dois.
    var anotando by remember { mutableStateOf<LinhaDoCarrinho?>(null) }
    var escolhendo by remember { mutableStateOf<ItemDoCardapio?>(null) }

    anotando?.let { linha ->
        DialogoDeObservacao(
            titulo = linha.item.titulo,
            atual = linha.observacao.orEmpty(),
            aoSalvar = { texto ->
                acoes.anotarObservacao(linha.id, texto)
                anotando = null
            },
            aoFechar = { anotando = null },
        )
    }

    escolhendo?.let { item ->
        DialogoDeEscolhas(
            item = item,
            aoConfirmar = { escolhas ->
                acoes.porNoCarrinho(item, escolhas)
                escolhendo = null
            },
            aoFechar = { escolhendo = null },
        )
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = acoes.voltar) { Text("← Conta") }
            Spacer(Modifier.weight(1f))
            Text(
                painel.conta?.mesa ?: "Cardápio",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        painel.aviso?.let {
            Spacer(Modifier.height(8.dp))
            Aviso(it, MaterialTheme.colorScheme.error)
        }

        if (painel.cardapio.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    if (painel.buscando) "Buscando o cardápio..." else "Cardápio vazio.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (categoria in painel.cardapio) {
                    item(key = "cat-${categoria.id}") {
                        Text(
                            categoria.nome.uppercase(),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                        )
                    }
                    items(categoria.itens, key = { it.cardapioItemId }) { item ->
                        ItemNoCardapio(
                            item = item,
                            // Todas as linhas deste item: com escolhas, o mesmo
                            // produto aparece mais de uma vez na rodada.
                            linhas = painel.carrinho
                                .filter { it.item.cardapioItemId == item.cardapioItemId },
                            aoPor = {
                                // Item com grupo pergunta antes; sem grupo, vai
                                // direto — perguntar nada seria um toque a mais
                                // em cada cerveja.
                                if (item.grupos.isEmpty()) {
                                    acoes.porNoCarrinho(item, emptyList())
                                } else {
                                    escolhendo = item
                                }
                            },
                            aoTirar = { acoes.tirarDoCarrinho(it) },
                            aoAnotar = { anotando = it },
                        )
                    }
                }
            }
        }

        Button(
            onClick = acoes.lancarRodada,
            enabled = painel.carrinho.isNotEmpty() && !painel.buscando,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth().height(60.dp),
        ) {
            // O que vai e quanto soma, no botão: é a última conferência antes
            // de a rodada chegar à chapa.
            Text(
                when {
                    painel.buscando -> "Lançando..."
                    painel.carrinho.isEmpty() -> "Escolha os itens"
                    else -> "Lançar ${painel.carrinho.sumOf { it.quantidade }} item(ns) · " +
                        emReais(painel.totalDoCarrinhoEmCentavos)
                },
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * A observação do item, escrita na mesa.
 *
 * Num diálogo, e não numa tela à parte: o garçom está no meio da rodada, com a
 * mesa falando, e não pode perder de vista o que já escolheu.
 */
@Composable
private fun DialogoDeObservacao(
    titulo: String,
    atual: String,
    aoSalvar: (String) -> Unit,
    aoFechar: () -> Unit,
) {
    var texto by remember(titulo) { mutableStateOf(atual) }

    AlertDialog(
        onDismissRequest = aoFechar,
        title = { Text(titulo, fontSize = 17.sp) },
        text = {
            Column {
                Text(
                    "Vai escrito no papel da cozinha.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = texto,
                    // O corte é aqui também, e não só no servidor: o garçom
                    // percebe o limite enquanto escreve, em vez de levar um
                    // erro depois de montar a rodada inteira.
                    onValueChange = { if (it.length <= 200) texto = it },
                    placeholder = { Text("sem cebola, ponto para a mesa 4...") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { aoSalvar(texto) }) { Text("Salvar") } },
        dismissButton = {
            // Limpar é o caminho de quem anotou errado: sem ele, só apagando
            // letra por letra no teclado da maquininha.
            TextButton(onClick = { aoSalvar("") }) { Text("Limpar") }
        },
    )
}


/**
 * O diálogo das escolhas: ponto da carne, adicionais, acompanhamento.
 *
 * Abre no toque do "+" de quem tem grupo, e só deixa confirmar quando o
 * obrigatório está atendido — é melhor travar o botão aqui, com o grupo à
 * vista, do que deixar o servidor recusar a rodada inteira depois.
 */
@Composable
private fun DialogoDeEscolhas(
    item: ItemDoCardapio,
    aoConfirmar: (List<OpcionalDoCardapio>) -> Unit,
    aoFechar: () -> Unit,
) {
    // Por grupo, o que está marcado. `remember` pela chave do item: trocar de
    // prato começa de novo.
    val marcados = remember(item.cardapioItemId) {
        mutableStateMapOf<String, MutableList<OpcionalDoCardapio>>()
    }

    fun doGrupo(grupoId: String) = marcados[grupoId] ?: mutableListOf()

    val escolhas = item.grupos.flatMap { doGrupo(it.id) }
    val faltando = item.grupos.filter { doGrupo(it.id).size < it.minimoDeVerdade }
    val extra = escolhas.sumOf { it.precoEmCentavos }

    AlertDialog(
        onDismissRequest = aoFechar,
        title = { Text(item.titulo, fontSize = 17.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (grupo in item.grupos) {
                    Text(
                        buildString {
                            append(grupo.titulo)
                            // O que o grupo exige, em palavras: "escolha 1" é
                            // mais claro que um asterisco de obrigatório.
                            append(
                                when {
                                    grupo.minimoDeVerdade > 0 && grupo.escolhaUnica -> " · escolha 1"
                                    grupo.minimoDeVerdade > 0 ->
                                        " · escolha ${grupo.minimoDeVerdade} a ${grupo.maxEscolhas}"
                                    grupo.maxEscolhas == 1 -> " · opcional"
                                    else -> " · até ${grupo.maxEscolhas}, opcional"
                                }
                            )
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                    )

                    for (opcional in grupo.itens) {
                        val marcado = doGrupo(grupo.id).any { it.opcionalId == opcional.opcionalId }
                        val cheio = doGrupo(grupo.id).size >= grupo.maxEscolhas
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 44.dp)
                                .clickable {
                                    val atuais = doGrupo(grupo.id).toMutableList()
                                    when {
                                        marcado -> atuais.removeAll {
                                            it.opcionalId == opcional.opcionalId
                                        }
                                        // Grupo de uma escolha troca em vez de
                                        // recusar: o garçom toca no ponto certo
                                        // sem precisar desmarcar o errado.
                                        grupo.escolhaUnica -> {
                                            atuais.clear()
                                            atuais.add(opcional)
                                        }
                                        !cheio -> atuais.add(opcional)
                                    }
                                    marcados[grupo.id] = atuais
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (marcado) "●" else "○",
                                fontSize = 15.sp,
                                color = if (marcado) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outline
                                },
                                modifier = Modifier.padding(end = 10.dp),
                            )
                            Text(
                                opcional.titulo,
                                fontSize = 15.sp,
                                modifier = Modifier.weight(1f),
                                // Opção cheia fica apagada: o grupo já tem o
                                // que aceita, e tocar nela não faria nada.
                                color = if (!marcado && cheio && !grupo.escolhaUnica) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                            if (opcional.precoEmCentavos > 0) {
                                Text(
                                    "+ ${emReais(opcional.precoEmCentavos)}",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { aoConfirmar(escolhas) },
                enabled = faltando.isEmpty(),
            ) {
                Text(
                    if (faltando.isNotEmpty()) {
                        "Falta ${faltando.first().titulo}"
                    } else {
                        "Pôr · ${emReais(item.precoEmCentavos + extra)}"
                    }
                )
            }
        },
        dismissButton = { TextButton(onClick = aoFechar) { Text("Cancelar") } },
    )
}

@Composable
private fun ItemNoCardapio(
    item: ItemDoCardapio,
    linhas: List<LinhaDoCarrinho>,
    aoPor: () -> Unit,
    aoTirar: (String) -> Unit,
    aoAnotar: (LinhaDoCarrinho) -> Unit,
) {
    val total = linhas.sumOf { it.quantidade }
    Surface(
        color = if (total > 0) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        item.titulo,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        // Esgotado fica na lista, apagado: sumir faria o garçom
                        // procurar um prato que ele sabe que existe.
                        color = if (item.esgotado) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    Text(
                        buildString {
                            append(if (item.esgotado) "esgotado" else emReais(item.precoEmCentavos))
                            // Avisa que vai perguntar, antes do toque: o garçom
                            // sabe que este prato tem escolha a fazer.
                            if (!item.esgotado && item.grupos.isNotEmpty()) {
                                append(" · ")
                                append(item.grupos.joinToString(", ") { it.titulo.lowercase() })
                            }
                        },
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (!item.esgotado) {
                    if (total > 0) {
                        Text(
                            "$total",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp),
                        )
                    }
                    TextButton(onClick = aoPor) { Text("+", fontSize = 22.sp) }
                }
            }

            /*
             * Uma linha por combinação escolhida, com o que foi marcado à
             * vista. Sem isto, duas picanhas com pontos diferentes apareceriam
             * como "2x" e o garçom não teria como conferir a rodada antes de
             * mandar — nem saber de qual delas o "−" vai tirar.
             */
            for (linha in linhas) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            buildString {
                                append("${linha.quantidade}x")
                                if (linha.escolhas.isNotEmpty()) {
                                    append(" · ")
                                    append(linha.escolhas.joinToString(", ") { it.titulo })
                                }
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(
                            onClick = { aoAnotar(linha) },
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.heightIn(min = 26.dp),
                        ) {
                            Text(
                                linha.observacao?.let { "“$it”" } ?: "+ observação",
                                fontSize = 12.sp,
                                textAlign = TextAlign.Start,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Text(
                        emReais(linha.precoDaLinhaEmCentavos),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { aoTirar(linha.id) }) { Text("−", fontSize = 20.sp) }
                }
            }
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
