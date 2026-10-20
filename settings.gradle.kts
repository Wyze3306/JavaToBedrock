pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenLocal()
        mavenCentral()
        // MCProtocolLib (GeyserMC)
        maven("https://repo.opencollab.dev/maven-releases/")
        maven("https://repo.opencollab.dev/maven-snapshots/") {
            mavenContent { snapshotsOnly() }
        }
        // PowerNukkitX Bedrock protocol library
        maven("https://repo.powernukkitx.org/releases")
    }
}

rootProject.name = "JavaToBedrock"
