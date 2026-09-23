import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.0"
    id("org.jetbrains.compose") version "1.10.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0"
}

group = "ai.rever.boss.plugin.dynamic"
version = "0.2.1"

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

val useLocalDependencies = System.getenv("CI") != "true"
val apiVersion = rootProject.file(".boss-plugin-api-version").readText().trim()

dependencies {
    val apiJar = if (useLocalDependencies) {
        files("../boss-plugin-api/build/libs/boss-plugin-api-$apiVersion.jar")
    } else {
        files("build/downloaded-deps/boss-plugin-api.jar")
    }
    compileOnly(apiJar)
    testImplementation(apiJar)

    implementation(compose.desktop.currentOs)
    implementation(compose.runtime)
    implementation(compose.ui)
    implementation(compose.foundation)
    implementation(compose.material)
    implementation(compose.materialIconsExtended)
    implementation("com.arkivanov.decompose:decompose:3.3.0")
    implementation("com.arkivanov.essenty:lifecycle:2.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // Parent-first host dependency. It must not be embedded in the plugin jar.
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

tasks.register<Jar>("buildPluginJar") {
    archiveFileName.set("boss-plugin-jev-$version.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(
            "Implementation-Title" to "BOSS Jev Plugin",
            "Implementation-Version" to version,
            "Main-Class" to "ai.rever.boss.plugin.dynamic.jev.JevDynamicPlugin",
        )
    }
    from(sourceSets.main.get().output)
}

// Only buildPluginJar's output belongs in build/libs: the release uploads it and
// publishes the largest jar there to the Plugin Store.
tasks.jar { enabled = false }

tasks.processResources {
    inputs.property("pluginVersion", version)
    filesMatching("**/plugin.json") {
        filter { it.replace(Regex("\"version\"\\s*:\\s*\"[^\"]*\""), "\"version\": \"$version\"") }
    }
}

tasks.withType<Test>().configureEach { systemProperty("java.awt.headless", "true") }
tasks.build { dependsOn("buildPluginJar") }
