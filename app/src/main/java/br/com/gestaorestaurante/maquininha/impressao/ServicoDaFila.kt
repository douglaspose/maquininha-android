package br.com.gestaorestaurante.maquininha.impressao

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import br.com.gestaorestaurante.maquininha.MainActivity
import br.com.gestaorestaurante.maquininha.PlataformaAtual
import br.com.gestaorestaurante.maquininha.R
import br.com.gestaorestaurante.maquininha.api.Api
import br.com.gestaorestaurante.maquininha.config.RepositorioDeConfiguracao
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * O aplicativo como agente de impressão da unidade.
 *
 * A maquininha tem impressora embutida, então ela mesma pode ser quem busca a
 * fila — some o computador do balcão rodando o agente em Node. Roda em
 * primeiro plano porque não pode dormir: pedido que não imprime é pedido que
 * a cozinha não vê.
 *
 * O laço é o mesmo do agente: busca, imprime, confirma. Quem confirma o que
 * saiu é o servidor, e trabalho que falhou volta para a fila lá — aqui não se
 * guarda estado nenhum, de propósito: maquininha reiniciada não perde pedido.
 */
class ServicoDaFila : LifecycleService() {

    private val api by lazy { Api(RepositorioDeConfiguracao(applicationContext)) }
    private val impressora by lazy { PlataformaAtual.impressora(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        criarCanal()
        startForeground(NOTIFICACAO, notificacao("Aguardando pedidos"))
        lifecycleScope.launch { laco() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // START_STICKY: se o Android matar o serviço por memória, ele volta.
        return START_STICKY
    }

    private suspend fun laco() {
        while (lifecycleScope.isActive) {
            val descanso = try {
                val resposta = api.buscarFilaDeImpressao()
                for (trabalho in resposta.trabalhos) {
                    val marca = resposta.marca
                        ?.takeIf { trabalho.comMarca }
                        ?.let { escposParaMarca(it.escpos, it.largura, it.altura) }
                    val cupom = lerConteudoDaFila(trabalho.titulo, trabalho.conteudo, marca)
                        .paraLargura(impressora.colunas)
                    val falha = impressora.imprimir(cupom)
                    api.confirmarImpressao(trabalho.id, ok = falha == null, erro = falha)
                }
                atualizarNotificacao(
                    if (resposta.trabalhos.isEmpty()) "Aguardando pedidos"
                    else "${resposta.trabalhos.size} impressos"
                )
                // Fila cheia: volta já, porque o servidor entrega no máximo 20
                // por vez e pode haver mais esperando.
                if (resposta.trabalhos.size >= 20) 200L else INTERVALO
            } catch (e: Exception) {
                atualizarNotificacao(e.message ?: "Sem servidor")
                // Servidor fora do ar não merece uma chamada a cada 3s de cada
                // maquininha do salão.
                INTERVALO * 4
            }
            delay(descanso)
        }
    }

    private fun criarCanal() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val canal = NotificationChannel(
            CANAL,
            "Impressão",
            // Baixa: é um aviso de que o serviço está de pé, não um alarme.
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
    }

    private fun notificacao(texto: String) = NotificationCompat.Builder(this, CANAL)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(texto)
        .setSmallIcon(android.R.drawable.ic_menu_agenda)
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        )
        .build()

    private fun atualizarNotificacao(texto: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICACAO, notificacao(texto))
    }

    companion object {
        private const val CANAL = "fila-de-impressao"
        private const val NOTIFICACAO = 1
        private const val INTERVALO = 3000L

        fun ligar(contexto: Context) {
            contexto.startService(Intent(contexto, ServicoDaFila::class.java))
        }

        fun desligar(contexto: Context) {
            contexto.stopService(Intent(contexto, ServicoDaFila::class.java))
        }
    }
}

/**
 * A logomarca chega pronta em ESC/POS porque o contrato foi escrito para uma
 * térmica de rede, onde basta escrever os bytes. A impressora da maquininha
 * não fala ESC/POS: fala a SDK do fabricante, que quer um bitmap.
 *
 * Então desembrulha-se o raster de dentro do `GS v 0` e entrega-se o bitmap.
 * É menos trabalho do que mudar o contrato do servidor — e o servidor não
 * precisa saber que existe maquininha.
 */
internal fun escposParaMarca(base64: String, largura: Int, altura: Int): Cupom.Marca? {
    val bytes = try {
        Base64.decode(base64, Base64.DEFAULT)
    } catch (e: IllegalArgumentException) {
        return null
    }
    // `GS v 0` = 0x1D 0x76 0x30, seguido do modo e de xL xH yL yH.
    var i = 0
    while (i + 7 < bytes.size) {
        if (bytes[i] == 0x1D.toByte() && bytes[i + 1] == 0x76.toByte() &&
            bytes[i + 2] == 0x30.toByte()
        ) {
            val bytesPorLinha = (bytes[i + 4].toInt() and 0xFF) or
                ((bytes[i + 5].toInt() and 0xFF) shl 8)
            val linhas = (bytes[i + 6].toInt() and 0xFF) or
                ((bytes[i + 7].toInt() and 0xFF) shl 8)
            val inicio = i + 8
            val tamanho = bytesPorLinha * linhas
            if (tamanho <= 0 || inicio + tamanho > bytes.size) return null
            return Cupom.Marca(
                bytes = bytes.copyOfRange(inicio, inicio + tamanho),
                largura = if (largura > 0) largura else bytesPorLinha * 8,
                altura = if (altura > 0) altura else linhas,
            )
        }
        i++
    }
    return null
}
