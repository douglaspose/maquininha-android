package br.com.gestaorestaurante.maquininha.impressao

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
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
    private val impressora by lazy { ImpressoraDaCasa.de(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        criarCanal()
        /*
         * Recusado pelo sistema é desistir, não cair.
         *
         * Cada versão do Android aperta um pouco mais quem pode subir em
         * primeiro plano e de onde. Sem este cinto, uma regra nova vira laço
         * de falha no boot — o serviço nasce, o sistema recusa, o aplicativo
         * cai, o START_STICKY traz de volta —, e foi exatamente isso que o
         * Android 15 fez com o tipo `dataSync`.
         */
        try {
            startForeground(NOTIFICACAO, notificacao("Aguardando pedidos"))
        } catch (e: Exception) {
            Log.w("Maquininha", "o sistema não deixou a fila subir agora: ${e.message}", e)
            stopSelf()
            return
        }
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
                val resposta = api.buscarFilaDeImpressao(impressora.colunas)
                val lote = LoteDeImpressao(
                    montar = { trabalho ->
                        val marca = resposta.marca
                            ?.takeIf { trabalho.comMarca }
                            ?.let { escposParaMarca(it.escpos) }
                        lerConteudoDaFila(trabalho.titulo, trabalho.conteudo, marca)
                            /*
                             * Estreitar aqui só quando o servidor não
                             * estreitou.
                             *
                             * Ele agora monta o papel na largura pedida, e o
                             * que vem assim já está certo — reflui-lo de novo
                             * só estragaria. O adaptador fica para o servidor
                             * antigo, que ignora o parâmetro e responde em 48.
                             */
                            .let {
                                if (resposta.colunas == impressora.colunas) it
                                else it.paraLargura(impressora.colunas)
                            }
                    },
                    imprimir = { cupom -> impressora.imprimir(cupom) },
                    confirmar = { id, ok, erro -> api.confirmarImpressao(id, ok = ok, erro = erro) },
                )
                val feito = lote.rodar(resposta.trabalhos)

                atualizarNotificacao(
                    when {
                        resposta.trabalhos.isEmpty() -> "Aguardando pedidos"
                        // O erro aparece na notificação: papel que não saiu é
                        // comanda que a cozinha não viu, e alguém no salão
                        // precisa ir olhar a impressora.
                        feito.comErro > 0 -> "${feito.impressos} impressos, ${feito.comErro} com erro"
                        else -> "${feito.impressos} impressos"
                    }
                )

                when {
                    // A rede caiu no meio do lote: espera como se o servidor
                    // tivesse sumido, que foi o que aconteceu.
                    feito.interrompido -> INTERVALO * 4
                    // Fila cheia: volta já, porque o servidor entrega no
                    // máximo 20 por vez e pode haver mais esperando.
                    resposta.trabalhos.size >= 20 -> 200L
                    else -> INTERVALO
                }
            } catch (e: Exception) {
                /*
                 * A notificação cabe uma linha; o logcat cabe a causa. Sem
                 * este log, "Sem resposta do servidor" é tudo o que sobra
                 * para diagnosticar uma maquininha que não imprime — e a
                 * diferença entre DNS, certificado e porta fechada é o que
                 * resolve o chamado.
                 */
                Log.w("Maquininha", "fila de impressão: ${e.message}", e)
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
            /*
             * `startForegroundService`, e não `startService`: do Android 8 em
             * diante um serviço iniciado com o aplicativo em segundo plano é
             * recusado na hora. O contrato dele é chamar `startForeground`
             * em até cinco segundos, e é a primeira coisa que o `onCreate`
             * faz.
             */
            ContextCompat.startForegroundService(
                contexto,
                Intent(contexto, ServicoDaFila::class.java),
            )
        }

        fun desligar(contexto: Context) {
            contexto.stopService(Intent(contexto, ServicoDaFila::class.java))
        }
    }
}
