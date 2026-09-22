package br.com.gestaorestaurante.maquininha.impressao

/**
 * A impressora embutida da maquininha, vista de fora.
 *
 * O servidor já entrega o cupom pronto em texto — o aplicativo não decide
 * layout, só repassa. O que muda de aparelho para aparelho é a largura e o
 * jeito de mandar, e é isso que esta interface esconde.
 */
interface Impressora {
    /** Quantos caracteres cabem numa linha na fonte padrão. */
    val colunas: Int

    /** Imprime e corta. Volta `null` se saiu; volta o motivo se não saiu. */
    suspend fun imprimir(cupom: Cupom): String?
}

/**
 * Um cupom pronto para sair, linha a linha.
 *
 * A fila do servidor guarda o texto com marcadores SO (0x0E) e SI (0x0F) em
 * volta das linhas em letra dupla — o Postgres não aceita byte zero em coluna
 * de texto, e o comando ESC/POS que desliga a letra dupla termina em zero.
 * Aqui esses marcadores já viraram a flag [Linha.letraDupla], e cada
 * implementação resolve do seu jeito: ESC/POS numa térmica de rede, chamada
 * de SDK na maquininha.
 */
data class Cupom(
    val titulo: String,
    val linhas: List<Linha>,
    /** A logomarca da casa, quando o trabalho pede que ela saia. */
    val marca: Marca? = null,
) {
    data class Linha(val texto: String, val letraDupla: Boolean = false)

    /**
     * A logo como raster de 1 bit, linha a linha, `largura / 8` bytes por
     * linha e o bit mais significativo à esquerda — o miolo do `GS v 0` que o
     * servidor manda, já desembrulhado por `escposParaMarca`.
     *
     * Desembrulhado porque a impressora da maquininha não fala ESC/POS: a SDK
     * do fabricante quer um bitmap, e montar um a partir disto é uma conta,
     * não uma biblioteca de imagem.
     */
    data class Marca(val bytes: ByteArray, val largura: Int, val altura: Int) {
        // `ByteArray` compara por identidade; sem isto, dois cupons iguais
        // vindos da mesma resposta apareceriam como diferentes.
        override fun equals(other: Any?): Boolean =
            this === other || (other is Marca &&
                largura == other.largura && altura == other.altura &&
                bytes.contentEquals(other.bytes))

        override fun hashCode(): Int =
            bytes.contentHashCode() * 31 * 31 + largura * 31 + altura
    }
}

private const val SO = '\u000E'
private const val SI = '\u000F'

/**
 * Lê o texto da fila e devolve as linhas já sabendo quais saem em letra dupla.
 *
 * O contrato do servidor está em `docs/impressao.md` do repositório do
 * sistema: linhas em letra dupla chegam entre SO e SI.
 */
fun lerConteudoDaFila(titulo: String, conteudo: String, marca: Cupom.Marca?): Cupom {
    val linhas = conteudo.split('\n').map { bruta ->
        val dupla = bruta.contains(SO)
        Cupom.Linha(
            texto = bruta.filter { it != SO && it != SI },
            letraDupla = dupla,
        )
    }
    return Cupom(titulo = titulo, linhas = linhas, marca = marca)
}
