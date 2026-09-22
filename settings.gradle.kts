import java.util.Properties

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\.android.*")
                includeGroupByRegex("com\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()

        /*
         * A SDK da Stone não está no Maven Central: mora num repositório
         * privado do PackageCloud e o endereço já embute o token que a Stone
         * manda por e-mail no onboarding. Por isso ele vem do
         * `local.properties` (que não entra no git) e não daqui.
         *
         * Sem o token o repositório simplesmente não é registrado — quem só
         * vai rodar o flavor `simulado` clona, abre e compila sem ter nada da
         * Stone na máquina.
         */
        val local = Properties().apply {
            val arquivo = File(rootDir, "local.properties")
            if (arquivo.exists()) arquivo.inputStream().use { load(it) }
        }
        val tokenStone = local.getProperty("stone.packagecloud.token")
            ?: System.getenv("STONE_PACKAGECLOUD_TOKEN")
        if (!tokenStone.isNullOrBlank()) {
            maven {
                name = "stone-pos-android"
                url = uri("https://packagecloud.io/priv/$tokenStone/stone/pos-android/maven2")
                content { includeGroup("br.com.stone") }
            }
        }
    }
}

rootProject.name = "Maquininha"
include(":app")
