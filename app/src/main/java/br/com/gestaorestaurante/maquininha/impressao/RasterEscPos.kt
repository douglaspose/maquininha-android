package br.com.gestaorestaurante.maquininha.impressao

import android.util.Base64

/**
 * A logomarca chega pronta em ESC/POS porque o contrato foi escrito para uma
 * térmica de rede, onde basta escrever os bytes na porta. A impressora da
 * maquininha não fala ESC/POS: fala a SDK do fabricante, que quer um bitmap.
 *
 * Então desembrulha-se o raster de dentro do `GS v 0` e guarda-se só ele. É
 * menos trabalho do que mudar o contrato do servidor — e o servidor continua
 * sem precisar saber que existe maquininha.
 */

/** O que chega da API: base64 do bloco ESC/POS inteiro. */
fun escposParaMarca(base64: String): Cupom.Marca? {
    val bytes = try {
        Base64.decode(base64, Base64.DEFAULT)
    } catch (e: IllegalArgumentException) {
        // Base64 quebrado não pode derrubar a impressão do pedido: sem logo,
        // o cupom sai igual.
        return null
    }
    return rasterDoGsV0(bytes)
}

/**
 * Acha o `GS v 0` no meio do bloco e devolve o raster que vem depois dele.
 *
 * O comando é `1D 76 30 m xL xH yL yH`, e o que segue são `xL+xH*256` bytes
 * por linha, `yL+yH*256` linhas, um bit por ponto, o mais significativo à
 * esquerda.
 *
 * A largura sai daqui, e não do campo `largura` da resposta: aquele é o
 * tamanho da logo em pontos, que a conversão arredonda para cima até fechar
 * byte. Confiar nele deslocaria cada linha do bitmap em relação à anterior —
 * a logo sairia escorrendo para o lado.
 */
internal fun rasterDoGsV0(bytes: ByteArray): Cupom.Marca? {
    var i = 0
    while (i + 7 < bytes.size) {
        val eComando = bytes[i] == 0x1D.toByte() &&
            bytes[i + 1] == 0x76.toByte() &&
            bytes[i + 2] == 0x30.toByte()
        if (eComando) {
            val bytesPorLinha = (bytes[i + 4].toInt() and 0xFF) or
                ((bytes[i + 5].toInt() and 0xFF) shl 8)
            val linhas = (bytes[i + 6].toInt() and 0xFF) or
                ((bytes[i + 7].toInt() and 0xFF) shl 8)
            val inicio = i + 8
            val tamanho = bytesPorLinha * linhas
            if (tamanho <= 0 || inicio + tamanho > bytes.size) return null
            return Cupom.Marca(
                bytes = bytes.copyOfRange(inicio, inicio + tamanho),
                largura = bytesPorLinha * 8,
                altura = linhas,
            )
        }
        i++
    }
    return null
}
