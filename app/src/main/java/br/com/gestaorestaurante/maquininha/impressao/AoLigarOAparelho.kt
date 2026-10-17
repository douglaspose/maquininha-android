package br.com.gestaorestaurante.maquininha.impressao

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import br.com.gestaorestaurante.maquininha.config.RepositorioDeConfiguracao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * A fila volta sozinha quando a maquininha liga.
 *
 * O serviço só nascia quando alguém abria o aplicativo. Maquininha que
 * reiniciou de madrugada, que ficou sem bateria ou que o salão desligou no
 * fim da noite voltava muda: a cozinha simplesmente parava de receber comanda,
 * e ninguém percebia — não há tela avisando, porque o aparelho está na
 * tomada do balcão com a tela apagada. Alguém só descobria quando um cliente
 * perguntasse pelo prato.
 *
 * Sem token configurado, não liga nada: aparelho recém-tirado da caixa não tem
 * com quem falar, e uma notificação fixa de erro na barra seria o primeiro
 * contato da casa com o aplicativo.
 */
class AoLigarOAparelho : BroadcastReceiver() {

    override fun onReceive(contexto: Context, intencao: Intent) {
        if (intencao.action !in LIGOU) return

        /*
         * `goAsync` porque ler a configuração é disco.
         *
         * `onReceive` roda na thread principal e não pode esperar; sem isto, o
         * Android daria o receiver por terminado e poderia matar o processo no
         * meio da leitura.
         */
        val pendente = goAsync()
        val app = contexto.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (RepositorioDeConfiguracao(app).atual().completa) {
                    ServicoDaFila.ligar(app)
                }
            } catch (e: Exception) {
                Log.w("Maquininha", "não deu para ligar a fila no boot: ${e.message}", e)
            } finally {
                pendente.finish()
            }
        }
    }

    private companion object {
        /**
         * O segundo é dos aparelhos com partida rápida do fabricante, comuns
         * em maquininha: eles desligam para um estado intermediário e nunca
         * mandam o `BOOT_COMPLETED` padrão.
         */
        val LIGOU = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
        )
    }
}
