import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.0"
    id("org.jetbrains.compose") version "1.10.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0"
}

group = "ai.rever.boss.plugin.dynamic"
version = "0.2.4"

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
    val pluginVersion = version.toString()
    inputs.property("pluginVersion", pluginVersion)
    // Only the top-level version's value changes; a dependency's "version" is a range, and every other byte stays.
    doLast {
        val manifest = destinationDir.resolve("META-INF/boss-plugin/plugin.json")
        val text = manifest.readText()
        val root = groovy.json.JsonSlurper().parseText(text) as? Map<*, *>
        check(root?.get("version") is String) { "plugin.json needs a top-level string version" }
        fun stringEnd(open: Int): Int {
            var j = open + 1
            while (text[j] != '"') j += if (text[j] == '\\') 2 else 1
            return j
        }
        fun skipSpace(from: Int): Int {
            var j = from
            while (text[j].isWhitespace()) j++
            return j
        }
        val values = mutableListOf<IntRange>()
        var depth = 0
        var i = 0
        while (i < text.length) {
            when (text[i]) {
                '{', '[' -> depth++
                '}', ']' -> depth--
                '"' -> {
                    val end = stringEnd(i)
                    val next = skipSpace(end + 1)
                    val key = text.substring(i + 1, end)
                    i = end
                    if (depth == 1 && key == "version" && text[next] == ':') {
                        val value = skipSpace(next + 1)
                        check(text[value] == '"') { "plugin.json top-level version must be a string" }
                        i = stringEnd(value)
                        values += (value + 1) until i
                    }
                }
            }
            i++
        }
        check(values.size == 1) { "plugin.json must have exactly one top-level version; found ${values.size}" }
        val span = values.single()
        manifest.writeText(text.substring(0, span.first) + pluginVersion + text.substring(span.last + 1))
    }
}

tasks.withType<Test>().configureEach { systemProperty("java.awt.headless", "true") }
tasks.build { dependsOn("buildPluginJar") }
