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
/*
 * A paleta inteira, e não só `primary`.
 *
 * O que fica de fora vem do padrão do Material, que é lilás: a forma de
 * pagamento escolhida aparecia num chip lilás no meio de uma tela verde, e o
 * cinza das teclas era um cinza de outra família. Aqui as duas famílias são a
 * mesma — verde da casa para o que é escolha, cinza esverdeado para o resto.
 */
private val Escuro = darkColorScheme(
    primary = Color(0xFF7DD3A0),
    onPrimary = Color(0xFF00391E),
    primaryContainer = Color(0xFF17452F),
    onPrimaryContainer = Color(0xFFBFF0D2),
    secondaryContainer = Color(0xFF17452F),
    onSecondaryContainer = Color(0xFFBFF0D2),
    background = Color(0xFF0A0A0A),
    onBackground = Color(0xFFE6EBE8),
    surface = Color(0xFF141414),
    onSurface = Color(0xFFE6EBE8),
    surfaceVariant = Color(0xFF1F2621),
    onSurfaceVariant = Color(0xFFB2BDB6),
    outline = Color(0xFF3A443E),
    outlineVariant = Color(0xFF2A322D),
)

private val Claro = lightColorScheme(
    primary = Color(0xFF16663C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEEDB),
    onPrimaryContainer = Color(0xFF07301A),
    secondaryContainer = Color(0xFFD9EFE2),
    onSecondaryContainer = Color(0xFF0B3D23),
    background = Color(0xFFF4F7F5),
    onBackground = Color(0xFF121714),
    surface = Color.White,
    onSurface = Color(0xFF121714),
    surfaceVariant = Color(0xFFEDF2EF),
    onSurfaceVariant = Color(0xFF4C5B53),
    outline = Color(0xFFC3CEC7),
    outlineVariant = Color(0xFFDCE4DF),
)

@Composable
fun TemaDaMaquininha(conteudo: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Escuro else Claro,
        content = conteudo,
    )
}
