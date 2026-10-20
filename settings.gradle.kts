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
        // No snapshotsOnly() filter: it rejects pinned timestamped snapshots such as the RakNet
        // transport version shared with PowerNukkitX (2.0.0.CR4-20260922.202324-5).
        maven("https://repo.opencollab.dev/maven-snapshots/")
        // PowerNukkitX Bedrock protocol library
        maven("https://repo.powernukkitx.org/releases")
    }
}

rootProject.name = "JavaToBedrock"
