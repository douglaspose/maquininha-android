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
                "\"${local.getProperty("stone.code") ?: ""}\""
            )
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
