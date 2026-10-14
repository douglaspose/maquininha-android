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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.gestaorestaurante.maquininha.api.MesaLivre

/**
 * Abrir a mesa pelo aparelho.
 *
 * O garçom que recebe uma mesa nova tinha de voltar ao tablet do balcão só
 * para abri-la, e só então podia lançar o pedido pela maquininha. São duas
 * telas: escolher o número, que é procurar entre dezenas, e dizer quantas
 * pessoas são, que é responder uma pergunta.
 *
 * A conversa é a mesma do PDV de propósito — as mesmas opções de pessoas, o
 * campo de nome aparecendo só onde a casa o exige. Quem aprendeu a abrir mesa
 * no tablet não deveria reaprender no aparelho.
 */

/**
 * As mesmas opções do PDV, em duas fileiras.
 *
 * As oito lado a lado não cabem na largura da maquininha — é o mesmo motivo
 * que repartiu as formas de pagamento em duas fileiras.
 */
private val FILEIRAS_DE_PESSOAS = listOf(listOf(1, 2, 3, 4), listOf(5, 6, 8, 10))

@Composable
internal fun TelaDeEscolhaDaMesa(painel: Painel, acoes: Acoes) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = acoes.voltar) { Text("← Mesas") }
            Spacer(Modifier.weight(1f))
            Text("Abrir mesa", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }

        painel.aviso?.let { Aviso(it, MaterialTheme.colorScheme.error) }

        if (painel.livres.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    "Nenhuma mesa livre agora.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            /*
             * Em grade, e não em lista: o garçom procura um número, e numa
             * lista de uma coluna as 40 mesas da casa viravam rolagem até a
             * mesa 23. Três por linha é o que cabe sem o número encolher.
             */
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(painel.livres, key = { it.mesaId }) { mesa ->
                    MesaLivreNaGrade(mesa) { acoes.escolherMesaLivre(mesa) }
                }
            }
        }
    }
}

@Composable
private fun MesaLivreNaGrade(mesa: MesaLivre, aoTocar: () -> Unit) {
    Surface(
        onClick = aoTocar,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(vertical = 12.dp, horizontal = 8.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                mesa.numero,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            mesa.area?.let {
                Text(
                    it,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                "${mesa.capacidade} lug.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun TelaDeAberturaDaMesa(painel: Painel, acoes: Acoes) {
    val mesa = painel.mesaParaAbrir
    // Sem mesa escolhida não há o que abrir: volta a ser a tela de escolher.
    if (mesa == null) {
        TelaDeEscolhaDaMesa(painel, acoes)
        return
    }

    // A capacidade da mesa é o palpite inicial, como no PDV: a mesa de quatro
    // lugares costuma receber quatro pessoas.
    var pessoas by remember(mesa.mesaId) { mutableStateOf(mesa.capacidade.coerceIn(1, 99)) }
    var nome by remember(mesa.mesaId) { mutableStateOf("") }

    val faltaONome = painel.exigeNomeCliente && nome.isBlank()

    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = acoes.voltar) { Text("← Mesas livres") }
        }

        Column {
            Text("Mesa ${mesa.numero}", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            /*
             * O número de pessoas fica escrito aqui, e não só no botão aceso.
             *
             * A capacidade da mesa nem sempre está entre as opções — a mesa de
             * sete lugares abre com sete e nenhum botão acende. Sem esta linha,
             * o garçom não teria como saber com quantas pessoas vai abrir.
             */
            Text(
                buildString {
                    mesa.area?.let { append("$it · ") }
                    append("$pessoas pessoa")
                    if (pessoas > 1) append("s")
                },
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            "QUANTAS PESSOAS",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (fileira in FILEIRAS_DE_PESSOAS) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (n in fileira) {
                    val escolhido = pessoas == n
                    Surface(
                        onClick = { pessoas = n },
                        color =
                            if (escolhido) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.weight(1f).height(52.dp),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "$n",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color =
                                    if (escolhido) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                            )
                        }
                    }
                }
            }
        }

        // O campo só existe onde a casa exige o nome, como no PDV: em branco,
        // ele custaria um toque e uma leitura a cada mesa aberta.
        if (painel.exigeNomeCliente) {
            OutlinedTextField(
                value = nome,
                onValueChange = { nome = it },
                label = { Text("Nome do cliente") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        painel.aviso?.let { Aviso(it, MaterialTheme.colorScheme.error) }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = { acoes.abrirMesa(pessoas, nome) },
            enabled = !painel.buscando && !faltaONome,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Text(
                when {
                    painel.buscando -> "Abrindo..."
                    // O botão diz o que falta, em vez de ficar apagado sem
                    // explicação — é a mesma conversa do diálogo de escolhas.
                    faltaONome -> "Falta o nome do cliente"
                    else -> "Abrir mesa ${mesa.numero}"
                },
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
