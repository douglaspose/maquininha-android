package br.com.gestaorestaurante.maquininha

import br.com.gestaorestaurante.maquininha.impressao.rasterDoGsV0
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A logo que o servidor manda vem embrulhada em ESC/POS. Aqui se confere o
 * desembrulho — a parte que não depende do Android e que, se errar, faz a
 * logo sair escorrendo para o lado no papel.
 */
class RasterEscPosTest {

    /** Monta o que o servidor manda: centraliza, `GS v 0`, raster, volta. */
    private fun blocoEscPos(bytesPorLinha: Int, linhas: Int, recheio: Byte = 0x5A): ByteArray {
        val cabecalho = byteArrayOf(
            0x1B, 0x61, 0x01, // ESC a 1 — centraliza
            0x1D, 0x76, 0x30, // GS v 0
            0x00, // modo normal
            (bytesPorLinha and 0xFF).toByte(), (bytesPorLinha shr 8).toByte(),
            (linhas and 0xFF).toByte(), (linhas shr 8).toByte(),
        )
        val raster = ByteArray(bytesPorLinha * linhas) { recheio }
        val rodape = byteArrayOf(0x1B, 0x61, 0x00, 0x0A) // ESC a 0 e quebra
        return cabecalho + raster + rodape
    }

    @Test
    fun `acha o raster no meio do bloco`() {
        val marca = rasterDoGsV0(blocoEscPos(bytesPorLinha = 48, linhas = 24))

        requireNotNull(marca)
        assertEquals(48 * 24, marca.bytes.size)
        assertEquals(24, marca.altura)
        // A largura sai do comando, não do campo da resposta: 48 bytes por
        // linha são 384 pontos.
        assertEquals(384, marca.largura)
        assertEquals(0x5A.toByte(), marca.bytes.first())
        assertEquals(0x5A.toByte(), marca.bytes.last())
    }

    @Test
    fun `logo mais larga que um byte de contagem`() {
        // 300 bytes por linha passam de 255: o segundo byte do tamanho tem
        // que ser lido, senão a logo vira uma tira fina.
        val marca = rasterDoGsV0(blocoEscPos(bytesPorLinha = 300, linhas = 2))

        requireNotNull(marca)
        assertEquals(600, marca.bytes.size)
        assertEquals(2400, marca.largura)
    }

    @Test
    fun `bloco truncado nao vira bitmap torto`() {
        // Diz que tem 48x24 e entrega menos: melhor cupom sem logo do que
        // uma logo lida para fora do array.
        val truncado = blocoEscPos(bytesPorLinha = 48, linhas = 24).copyOf(200)

        assertNull(rasterDoGsV0(truncado))
    }

    @Test
    fun `sem GS v 0 nao ha marca`() {
        assertNull(rasterDoGsV0(byteArrayOf(0x1B, 0x61, 0x01, 0x0A)))
    }

    @Test
    fun `tamanho zero nao vira marca vazia`() {
        assertNull(rasterDoGsV0(blocoEscPos(bytesPorLinha = 0, linhas = 0)))
    }
}
