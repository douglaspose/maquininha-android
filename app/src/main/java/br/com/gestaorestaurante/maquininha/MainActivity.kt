package br.com.gestaorestaurante.maquininha

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import br.com.gestaorestaurante.maquininha.ui.Acoes
import br.com.gestaorestaurante.maquininha.ui.ModeloDaTela
import br.com.gestaorestaurante.maquininha.ui.Tela
import br.com.gestaorestaurante.maquininha.ui.TemaDaMaquininha

/**
 * A única tela do aplicativo.
 *
 * Uma maquininha não tem "voltar para a lista": ela tem um valor, um cartão e
 * um comprovante. Tudo o que existe aqui cabe num Activity só, e o que muda é
 * o estado, não a navegação.
 */
class MainActivity : ComponentActivity() {

    private val pedirNotificacao = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Negado é aceitável: o serviço roda, só não aparece na barra. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        /*
         * Do Android 15 em diante a janela é sempre de borda a borda, quer o
         * aplicativo peça ou não: sem isto o título encosta no relógio e o
         * botão de cobrar fica atrás da barra de navegação. `enableEdgeToEdge`
         * declara a intenção e `safeDrawingPadding` devolve o espaço das duas
         * barras ao conteúdo.
         */
        enableEdgeToEdge()
        garantirNotificacao()

        setContent {
            TemaDaMaquininha {
                Surface(
                    modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val modelo: ModeloDaTela = viewModel()
                    val painel by modelo.painel.collectAsState()
                    Tela(
                        painel = painel,
                        acoes = Acoes(
                            digitar = modelo::digitar,
                            apagar = modelo::apagar,
                            limpar = modelo::limpar,
                            escolherForma = modelo::escolherForma,
                            escolherParcelas = modelo::escolherParcelas,
                            cobrar = modelo::cobrar,
                            configurar = modelo::configurar,
                            abrirConfiguracao = modelo::abrirConfiguracao,
                            entrarComPin = modelo::entrarComPin,
                            sairDoTurno = modelo::sairDoTurno,
                            atualizarMesas = modelo::atualizarMesas,
                            irParaAbertura = modelo::irParaAbertura,
                            escolherMesaLivre = modelo::escolherMesaLivre,
                            abrirMesa = modelo::abrirMesa,
                            abrirConta = modelo::abrirConta,
                            abrirCardapio = modelo::abrirCardapio,
                            pedirAConta = modelo::pedirAConta,
                            reabrirConta = modelo::reabrirConta,
                            porNoCarrinho = modelo::porNoCarrinho,
                            tirarDoCarrinho = modelo::tirarDoCarrinho,
                            lancarRodada = modelo::lancarRodada,
                            anotarObservacao = modelo::anotarObservacao,
                            cobrarDaConta = modelo::cobrarDaConta,
                            cobrancaAvulsa = modelo::cobrancaAvulsa,
                            voltar = modelo::voltar,
                        ),
                    )
                }
            }
        }
    }

    /**
     * Do Android 13 em diante um serviço em primeiro plano sem permissão de
     * notificação roda, mas invisível. Numa maquininha isso importa: a
     * notificação é o único lugar onde o operador vê que a fila de impressão
     * está de pé e qual foi o último erro.
     */
    private fun garantirNotificacao() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val concedida = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!concedida) pedirNotificacao.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
