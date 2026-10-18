package br.com.gestaorestaurante.maquininha.ui

/**
 * Dinheiro para a tela.
 *
 * Fica num arquivo sem uma linha de Compose porque é a única parte da camada
 * de tela que dá para testar na JVM, sem emulador — e porque um erro de
 * centavo aqui aparece justamente no visor que o cliente confere.
 */

/**
 * 1550 vira "R$ 15,50". Sem Locale do sistema: a maquininha pode estar em en-US.
 *
 * O sinal sai na frente, e não no meio: o que já foi pago aparece na conta
 * como valor negativo, e a primeira versão mostrava "R$ -22,-94".
 */
fun emReais(centavos: Long): String {
    val sinal = if (centavos < 0) "-" else ""
    val absoluto = if (centavos < 0) -centavos else centavos
    val reais = absoluto / 100
    val resto = absoluto % 100
    val comPontos = reais.toString().reversed().chunked(3).joinToString(".").reversed()
    return "R$ $sinal$comPontos,${resto.toString().padStart(2, '0')}"
}

/**
 * Há quanto tempo: "agora", "há 3 min", "há 1h12".
 *
 * O servidor manda o instante em ISO 8601 UTC; o que o garçom precisa ler é a
 * espera, não a hora. Quem está de mão levantada conta os minutos, e é por eles
 * que ele decide a qual mesa vai primeiro.
 *
 * `SimpleDateFormat`, e não `java.time`: o pacote só existe do Android 8 em
 * diante, e estas maquininhas vão até o 7. O formato é fixo porque quem o
 * escreve é o `toISOString()` do servidor, sempre com três casas e Z no fim.
 *
 * Data que não dá para ler devolve nulo em vez de quebrar a lista do salão:
 * uma mesa sem o "há 3 min" ainda é uma mesa; uma tela que estourou, não.
 */
/**
 * Um formatador por thread, e não um por chamada.
 *
 * `esperaDesde` é chamada uma vez por mesa a cada redesenho do salão — e o
 * salão se redesenha sozinho a cada ronda. Construir um `SimpleDateFormat`
 * ali dentro significa, numa casa de quarenta mesas, quarenta objetos por
 * redesenho, cada um montando o calendário e o fuso.
 *
 * Por thread, e não um só compartilhado, porque `SimpleDateFormat` guarda
 * estado interno enquanto analisa: duas threads no mesmo objeto devolvem data
 * trocada ou estouram, e o que sai disso é uma espera errada na tela do garçom
 * — ou a lista do salão inteira sem os minutos.
 */
private val FORMATO_ISO = object : ThreadLocal<java.text.SimpleDateFormat>() {
    override fun initialValue() =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
}

fun esperaDesde(iso: String, agoraEmMillis: Long = System.currentTimeMillis()): String? {
    val quando = try {
        FORMATO_ISO.get()!!.parse(iso)?.time ?: return null
    } catch (e: java.text.ParseException) {
        return null
    }

    val minutos = (agoraEmMillis - quando) / 60_000
    // Relógio do aparelho atrasado em relação ao servidor daria "há -2 min".
    if (minutos < 1) return "agora"
    if (minutos < 60) return "há $minutos min"
    val horas = minutos / 60
    return "há ${horas}h${(minutos % 60).toString().padStart(2, '0')}"
}

/**
 * 1.0 vira "1"; 0,5 continua "0,5" — a cozinha lança meia porção.
 *
 * Estava escondida na tela do salão; veio para cá quando a tela do
 * cancelamento precisou dela, que é o lugar dela desde o começo: é formato, e
 * dá para conferir sem emulador.
 */
fun quantidade(q: Double): String =
    if (q == q.toLong().toDouble()) q.toLong().toString() else q.toString().replace('.', ',')

/**
 * Um formatador de hora por thread, pelo mesmo motivo do de cima.
 *
 * No fuso do aparelho de propósito: quem lê esta hora é a casa, conferindo
 * contra o extrato da adquirente, e os dois estão no mesmo relógio.
 */
private val FORMATO_DA_HORA = object : ThreadLocal<java.text.SimpleDateFormat>() {
    override fun initialValue() =
        java.text.SimpleDateFormat("HH'h'mm", java.util.Locale.US)
}

/**
 * A hora de uma venda: "20h47".
 *
 * Zero é "sem hora": pagamento guardado por uma versão do aplicativo que ainda
 * não anotava quando a cobrança aconteceu. A lista mostra o que tem, em vez de
 * inventar uma hora ou esconder a venda.
 */
fun horaDe(millis: Long): String =
    if (millis <= 0) "sem hora" else FORMATO_DA_HORA.get()!!.format(java.util.Date(millis))
