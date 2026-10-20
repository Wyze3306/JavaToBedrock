plugins {
    java
    application
    alias(libs.plugins.shadow)
}

group = "io.github.wyze3306"
description = "JavaToBedrock - lets Minecraft Java Edition players join Bedrock Edition servers (PowerNukkitX, BDS...)"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

configurations.all {
    // MCProtocolLib and the Bedrock protocol library both pull stripped-down copies of fastutil;
    // we ship the complete library instead so every class is present exactly once.
    exclude(group = "com.nukkitx.fastutil")
    exclude(group = "org.cloudburstmc.fastutil.commons")
    exclude(group = "org.cloudburstmc.fastutil.maps")
    resolutionStrategy {
        // A single Netty version for the Java (TCP) and Bedrock (RakNet) sides.
        eachDependency {
            if (requested.group == "io.netty" && !requested.name.startsWith("netty-tcnative")) {
                useVersion(libs.versions.netty.get())
            }
        }
    }
}

dependencies {
    implementation(libs.mcprotocollib)
    implementation(libs.bedrock.connection)
    implementation(libs.netty.all)
    implementation(libs.netty.raknet)
    implementation(libs.fastutil)
    implementation(libs.gson)
    implementation(libs.snakeyaml)
    implementation(libs.adventure.legacy)
    implementation(libs.adventure.plain)
    implementation(libs.log4j.core)
    implementation(libs.log4j.slf4j)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

application {
    mainClass.set("io.github.wyze3306.jtb.JavaToBedrock")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-parameters"))
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
}

// End-to-end check against a running proxy: gradle runHeadlessClient -Phost=127.0.0.1 -Pport=25565
tasks.register<JavaExec>("runHeadlessClient") {
    group = "verification"
    description = "Joins a running proxy with a display-less Java client and prints what it received"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.wyze3306.jtb.e2e.HeadlessJavaClient")
    listOf("host", "port", "name", "seconds").forEach { key ->
        providers.gradleProperty(key).orNull?.let { systemProperty(key, it) }
    }
}

tasks.jar {
    manifest {
        attributes(
            "Main-Class" to "io.github.wyze3306.jtb.JavaToBedrock",
            "Implementation-Version" to project.version,
            "Multi-Release" to "true",
        )
    }
}

tasks.shadowJar {
    archiveBaseName.set("JavaToBedrock")
    archiveClassifier.set("")
    archiveVersion.set("")
    mergeServiceFiles()
    // log4j2 plugin cache files from several jars must be merged, not overwritten.
    transform(com.github.jengelman.gradle.plugins.shadow.transformers.Log4j2PluginsCacheFileTransformer::class.java)
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
