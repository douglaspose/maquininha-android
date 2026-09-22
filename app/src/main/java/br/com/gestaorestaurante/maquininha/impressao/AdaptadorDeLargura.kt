package br.com.gestaorestaurante.maquininha.impressao

/**
 * Encolhe um cupom montado para 48 colunas até a largura da maquininha.
 *
 * Isto é um remendo, e vale dizer por quê. O servidor monta o cupom em 48
 * colunas, que é a largura da térmica de 80mm do balcão. A impressora
 * embutida numa maquininha é de 58mm: cabem 32 caracteres, 16 em letra dupla.
 * Quem imprime 48 colunas em 32 perde o fim de cada linha — justo onde ficam
 * os valores.
 *
 * O certo é o servidor montar na largura do aparelho: `GET /api/impressao`
 * passa a aceitar a largura de quem está buscando, e some tudo o que está
 * neste arquivo. Enquanto isso não existe, o melhor esforço aqui é reconhecer
 * o par "descrição à esquerda, valor à direita" — que é a forma de quase toda
 * linha de cupom — e reaproximar os dois, quebrando a descrição se precisar.
 *
 * O que ele não conserta: tabela de três colunas, texto centralizado com
 * espaços e arte em ASCII. Esses saem tortos, e continuarão saindo até a
 * largura vir do servidor.
 */
fun Cupom.paraLargura(colunas: Int): Cupom {
    if (colunas <= 0) return this
    return copy(linhas = linhas.flatMap { it.paraLargura(colunas) })
}

private fun Cupom.Linha.paraLargura(colunas: Int): List<Cupom.Linha> {
    // Em letra dupla cada caractere ocupa dois, então cabe metade.
    val cabem = if (letraDupla) colunas / 2 else colunas
    val texto = this.texto.trimEnd()
    if (texto.length <= cabem) return listOf(copy(texto = texto))

    val separacao = Regex("\s{2,}").findAll(texto).lastOrNull()
    if (separacao != null) {
        val esquerda = texto.substring(0, separacao.range.first).trimEnd()
        val direita = texto.substring(separacao.range.last + 1).trim()
        if (direita.isNotEmpty() && direita.length < cabem) {
            val sobra = cabem - direita.length - 1
            return if (esquerda.length <= sobra) {
                listOf(copy(texto = esquerda.padEnd(sobra + 1) + direita))
            } else {
                // Não cabe lado a lado: a descrição fica na linha de cima e o
                // valor desce alinhado à direita, que é como o cupom de uma
                // maquininha costuma sair mesmo.
                listOf(
                    copy(texto = esquerda.take(cabem)),
                    copy(texto = direita.padStart(cabem)),
                )
            }
        }
    }

    // Sem par reconhecível: quebra seca, preferindo o espaço mais à direita.
    return sequence {
        var resto = texto
        while (resto.length > cabem) {
            val corte = resto.lastIndexOf(' ', cabem).takeIf { it > cabem / 2 } ?: cabem
            yield(copy(texto = resto.substring(0, corte).trimEnd()))
            resto = resto.substring(corte).trimStart()
        }
        if (resto.isNotEmpty()) yield(copy(texto = resto))
    }.toList()
}
