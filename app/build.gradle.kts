import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val local = Properties().apply {
    val arquivo = rootProject.file("local.properties")
    if (arquivo.exists()) arquivo.inputStream().use { load(it) }
}

/**
 * Um segredo do `local.properties`, ou da variável de ambiente equivalente.
 *
 * O arquivo serve na máquina de quem desenvolve; a variável serve no servidor
 * de build, que não tem `local.properties`. `stone.jks.senha` vira
 * `STONE_JKS_SENHA`.
 */
fun segredo(nome: String): String? =
    (local.getProperty(nome) ?: System.getenv(nome.replace('.', '_').uppercase()))
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

/*
 * A chave de assinatura da Stone.
 *
 * Não é uma chave nossa: a Stone entrega a JKS junto com a homologação, e é
 * com ela que o APK tem de ser assinado — inclusive para instalar no aparelho
 * de **debug**. Por isso a assinatura entra no flavor, e não no tipo de build:
 * `stoneDebug` e `stoneRelease` usam a mesma, enquanto o `simulado` continua
 * com a chave de depuração do Android e roda no emulador sem nada disso.
 *
 * Nada aqui é versionado. O caminho e as senhas vêm do `local.properties` (que
 * o git ignora) ou do ambiente, e a própria `.jks` fica fora do repositório.
 */
val jksStone = segredo("stone.jks.caminho")?.let { rootProject.file(it) }
val jksSenhaDaLoja = segredo("stone.jks.senha")
val jksAlias = segredo("stone.jks.alias")
// Quando a Stone usa a mesma senha para o cofre e para a chave, basta uma.
val jksSenhaDaChave = segredo("stone.jks.senhaDaChave") ?: jksSenhaDaLoja

val podeAssinarComAStone =
    jksStone != null && jksStone.exists() &&
        jksSenhaDaLoja != null && jksAlias != null && jksSenhaDaChave != null

if (jksStone != null && !jksStone.exists()) {
    logger.warn("Maquininha: stone.jks.caminho aponta para ${jksStone.absolutePath}, que não existe.")
}

android {
    namespace = "br.com.gestaorestaurante.maquininha"
    compileSdk = 35

    defaultConfig {
        applicationId = "br.com.gestaorestaurante.maquininha"
        /*
         * 24 (Android 7) é o chão das maquininhas que ainda estão em campo.
         * As Stone novas vêm com 10 ou 11, mas o parque é velho e subir o
         * mínimo sem necessidade só tira aparelho do ar.
         */
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    /*
     * Uma dimensão de flavor por credenciadora, e não um `if` dentro do
     * código: o SDK da Stone é uma dependência privada, pesada e que só roda
     * no hardware dela. Separado assim, o flavor `simulado` compila e roda no
     * emulador sem token nenhum, e é nele que quase todo o desenvolvimento
     * acontece.
     *
     * `rede` (a laranjinha do Itaú) entra como um terceiro flavor quando o
     * credenciamento sair — o resto do aplicativo não muda uma linha.
     */
    signingConfigs {
        // Só existe quando a chave existe: sem ela, o flavor `stone` sai sem
        // assinatura própria em vez de o build inteiro quebrar para quem está
        // mexendo no `simulado`.
        if (podeAssinarComAStone) {
            create("stone") {
                storeFile = jksStone
                storePassword = jksSenhaDaLoja
                keyAlias = jksAlias
                keyPassword = jksSenhaDaChave
            }
        }
    }

    flavorDimensions += "credenciadora"
    productFlavors {
        create("simulado") {
            dimension = "credenciadora"
            applicationIdSuffix = ".simulado"
            versionNameSuffix = "-simulado"
            resValue("string", "app_name", "Maquininha (simulada)")
        }
        create("stone") {
            dimension = "credenciadora"
            resValue("string", "app_name", "Maquininha")
            buildConfigField(
                "String",
                "STONE_CODE",
                "\"${segredo("stone.code") ?: ""}\""
            )
            // A JKS da Stone vale para debug e release deste flavor: sem ela o
            // APK não instala nem no aparelho de homologação.
            if (podeAssinarComAStone) signingConfig = signingConfigs.getByName("stone")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

/*
 * Sem a chave, o release do flavor `stone` sairia como `-unsigned.apk`: um
 * arquivo que parece pronto, não instala em maquininha nenhuma e só revela o
 * problema no dia da entrega. Melhor parar aqui, dizendo o que falta.
 *
 * O `simulado` não entra nesta trava — é com ele que se desenvolve, e ele não
 * depende de chave nenhuma.
 */
if (!podeAssinarComAStone) {
    /*
     * No grafo de tarefas, e não num `doFirst` da própria tarefa: assim a
     * reclamação sai antes de qualquer coisa rodar. Pendurada no `assemble`,
     * ela só aparecia depois do lint — minutos de espera para então descobrir
     * que faltava a chave desde o começo.
     */
    gradle.taskGraph.whenReady {
        val pedeReleaseDaStone = allTasks.any {
            it.name == "assembleStoneRelease" || it.name == "bundleStoneRelease"
        }
        if (pedeReleaseDaStone) {
            throw GradleException(
                """
                Falta a chave de assinatura da Stone.

                Ela vem da Stone junto com a homologação — não é uma chave que
                se gere aqui. Copie `local.properties.exemplo` para
                `local.properties` e preencha:

                  stone.jks.caminho=chaves/stone.jks
                  stone.jks.senha=...
                  stone.jks.alias=...

                Num servidor de build, as mesmas em STONE_JKS_CAMINHO,
                STONE_JKS_SENHA e STONE_JKS_ALIAS.
                """.trimIndent()
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    /*
     * Só o flavor `stone` puxa a SDK. O `stoneImplementation` é o que faz o
     * build do `simulado` nem procurar por ela — e é por isso que o
     * repositório privado do PackageCloud pode faltar sem quebrar nada.
     *
     * A versão fica aqui, e não no catálogo, porque quem confere se ela
     * existe é o onboarding da Stone: o número que vale é o do e-mail deles.
     */
    // "stoneImplementation"("br.com.stone:stone-sdk-posandroid:5.0.0")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
