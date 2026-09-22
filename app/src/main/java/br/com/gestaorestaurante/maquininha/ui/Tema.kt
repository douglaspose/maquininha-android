package br.com.gestaorestaurante.maquininha.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Cores fixas, e não as do sistema.
 *
 * Maquininha não é telefone de ninguém: o tema dinâmico do Android 12+ deixaria
 * a tela de cobrança com a cor do papel de parede do aparelho — que num parque
 * de trinta maquininhas quer dizer trinta aplicativos com cara diferente.
 */
private val Escuro = darkColorScheme(
    primary = Color(0xFF7DD3A0),
    onPrimary = Color(0xFF00391E),
    background = Color(0xFF0A0A0A),
    surface = Color(0xFF141414),
)

private val Claro = lightColorScheme(
    primary = Color(0xFF16663C),
    onPrimary = Color.White,
    background = Color(0xFFFAFAFA),
    surface = Color.White,
)

@Composable
fun TemaDaMaquininha(conteudo: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Escuro else Claro,
        content = conteudo,
    )
}
