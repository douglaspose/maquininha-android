package br.com.gestaorestaurante.maquininha

import android.content.Context
import br.com.gestaorestaurante.maquininha.impressao.Impressora
import br.com.gestaorestaurante.maquininha.pagamento.ProvedorDePagamento

/**
 * O que muda de uma maquininha para outra.
 *
 * Cada flavor (`src/stone`, `src/simulado`, e amanhã `src/rede`) declara um
 * `object PlataformaAtual : Plataforma`. O resto do aplicativo nunca importa
 * uma SDK de credenciadora — importa isto.
 */
interface Plataforma {
    /** Aparece na tela e vai junto no resultado da cobrança. */
    val credenciadora: String

    fun pagamento(contexto: Context): ProvedorDePagamento

    fun impressora(contexto: Context): Impressora
}
