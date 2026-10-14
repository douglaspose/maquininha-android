package br.com.gestaorestaurante.maquininha.ui

import br.com.gestaorestaurante.maquininha.api.ItemDoCardapio
import br.com.gestaorestaurante.maquininha.api.OpcionalDoCardapio

/**
 * As decisões da rodada em montagem, sem uma linha de Android.
 *
 * Mora fora do `ModeloDaTela` pelo mesmo motivo do `Formato.kt`: é a parte que
 * decide o que o garçom vê e o que a cozinha recebe, e é a única que dá para
 * conferir na JVM, sem emulador. O ViewModel cuida de estado, rede e disco; o
 * que está aqui é só lista entrando e saindo.
 *
 * Todas as funções devolvem uma lista nova — nada é alterado no lugar, que é o
 * que o `StateFlow` da tela espera para redesenhar.
 */

/**
 * Mais um deste item na rodada.
 *
 * Escolhas iguais somam na mesma linha; diferentes abrem outra. Duas picanhas
 * ao ponto são "2x"; uma ao ponto e uma mal passada são duas linhas, porque é
 * o que a cozinha vai ler e o que a conta vai cobrar.
 *
 * Linha com observação nunca recebe mais um: o "+" não tem como saber se o
 * recado vale para a unidade nova. Ela abre linha própria.
 */
fun porNaRodada(
    rodada: List<LinhaDoCarrinho>,
    item: ItemDoCardapio,
    escolhas: List<OpcionalDoCardapio> = emptyList(),
    /** Injetável só para o teste conseguir prever o id da linha. */
    novoId: () -> String = { java.util.UUID.randomUUID().toString() },
): List<LinhaDoCarrinho> {
    // Esgotado não entra: a tela já esconde o "+", e isto é o cinto.
    if (item.esgotado) return rodada

    val escolhidos = escolhas.map { it.opcionalId }.toSet()
    val existente = rodada.find {
        it.item.cardapioItemId == item.cardapioItemId &&
            it.observacao == null &&
            it.escolhas.map { e -> e.opcionalId }.toSet() == escolhidos
    }

    return if (existente == null) {
        rodada + LinhaDoCarrinho(
            id = novoId(),
            item = item,
            quantidade = 1,
            escolhas = escolhas,
        )
    } else {
        rodada.map { if (it.id == existente.id) it.copy(quantidade = it.quantidade + 1) else it }
    }
}

/** Menos um nesta linha. Chegando a zero, a linha sai da rodada. */
fun tirarDaRodada(rodada: List<LinhaDoCarrinho>, linhaId: String): List<LinhaDoCarrinho> =
    rodada
        .map { if (it.id == linhaId) it.copy(quantidade = it.quantidade - 1) else it }
        .filter { it.quantidade > 0 }

/**
 * A observação da linha — o que vai escrito no papel da cozinha.
 *
 * Em branco, some: observação vazia gravada vira linha que não agrupa com
 * nenhuma outra, e o garçom não entende por que dois chopes aparecem separados
 * na conta. O corte em 200 é o mesmo do servidor, feito aqui para o garçom
 * perceber o limite enquanto escreve.
 */
fun anotarNaRodada(
    rodada: List<LinhaDoCarrinho>,
    linhaId: String,
    texto: String,
): List<LinhaDoCarrinho> {
    val limpo = texto.trim().take(200).ifBlank { null }
    return rodada.map { if (it.id == linhaId) it.copy(observacao = limpo) else it }
}

/**
 * O dígito entra pela direita, como em toda maquininha: quem digita 1 5 0 0
 * quer R$ 15,00.
 *
 * O teto existe para o dedo pesado não virar uma conta de milhões — e porque
 * acima disso o visor não comporta o número.
 */
fun digitarCentavo(valorAtual: Long, digito: Char): Long {
    if (digito !in '0'..'9') return valorAtual
    val novo = valorAtual * 10 + (digito - '0')
    return if (novo > 99_999_999L) valorAtual else novo
}

/** Apaga o último dígito. */
fun apagarCentavo(valorAtual: Long): Long = valorAtual / 10
