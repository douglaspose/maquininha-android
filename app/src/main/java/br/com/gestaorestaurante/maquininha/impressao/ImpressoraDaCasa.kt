package br.com.gestaorestaurante.maquininha.impressao

import android.content.Context
import br.com.gestaorestaurante.maquininha.PlataformaAtual
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A bobina é uma só, e sai um papel por vez.
 *
 * Duas partes do aplicativo imprimem: o serviço da fila, que despeja as
 * comandas da cozinha, e a tela da cobrança, que tira a via do cliente logo
 * depois do "aprovada". Cada uma pedia a sua impressora à plataforma e saía
 * mandando — duas instâncias, nenhuma sabendo da outra, e a térmica no meio.
 *
 * Na bancada isso só embaralha linhas no log. Numa maquininha de verdade é o
 * SDK da credenciadora recebendo dois trabalhos ao mesmo tempo: no melhor caso
 * o segundo é recusado e o papel não sai; no pior, as duas vias saem
 * misturadas no mesmo pedaço de bobina — e o que o cliente leva é metade do
 * comprovante dele com metade da comanda da cozinha.
 */
object ImpressoraDaCasa {

    @Volatile
    private var dela: Impressora? = null

    /** A impressora do processo: uma instância só, atrás de uma fila. */
    fun de(contexto: Context): Impressora = dela ?: synchronized(this) {
        dela ?: UmaImpressaoPorVez(PlataformaAtual.impressora(contexto.applicationContext))
            .also { dela = it }
    }
}

/**
 * A fila em volta de uma impressora: quem chegar segundo espera o primeiro
 * cortar o papel.
 *
 * Separada do objeto acima porque o que precisa ser conferido é a regra, e não
 * a montagem — esta dá para testar sem `Context` e sem flavor.
 */
internal class UmaImpressaoPorVez(private val real: Impressora) : Impressora {

    private val tranca = Mutex()

    override val colunas: Int get() = real.colunas

    override suspend fun imprimir(cupom: Cupom): String? = tranca.withLock {
        real.imprimir(cupom)
    }
}
