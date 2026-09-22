package br.com.gestaorestaurante.maquininha.ui

/**
 * Dinheiro para a tela.
 *
 * Fica num arquivo sem uma linha de Compose porque é a única parte da camada
 * de tela que dá para testar na JVM, sem emulador — e porque um erro de
 * centavo aqui aparece justamente no visor que o cliente confere.
 */

/** 1550 vira "R$ 15,50". Sem Locale do sistema: a maquininha pode estar em en-US. */
fun emReais(centavos: Long): String {
    val reais = centavos / 100
    val resto = centavos % 100
    val comPontos = reais.toString().reversed().chunked(3).joinToString(".").reversed()
    return "R$ " + comPontos + "," + resto.toString().padStart(2, '0')
}
