import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    alias(libs.plugins.neogradle.userdev)
    alias(libs.plugins.shadow)
    `java-library`
}

val minecraftVersion = libs.versions.neoforge.minecraft.get()
val nextMajorMinecraftVersion: String = minecraftVersion.split('.').let { (useless, major) ->
    "$useless.${major.toInt() + 1}"
}

// WorldEdit's own classes have to be handed to the dev runs explicitly, otherwise the mod's
// module layer only sees this project and loading fails the first time it touches worldedit-core.
val apiClasspath = configurations.create("apiClasspath") {
    isCanBeResolved = true
    extendsFrom(configurations.api.get())
}

repositories {
    maven {
        name = "EngineHub"
        url = uri("https://maven.enginehub.org/repo/")
    }
    maven {
        name = "Terraformers"
        url = uri("https://maven.terraformersmc.com/")
    }
    mavenCentral()
}

dependencies {
    "api"(project(":worldedit-core"))

    "implementation"(libs.neoforge)

    // Applied Energistics 2 is an optional runtime dependency. It is vendored so builds are
    // reproducible without reaching a third-party Maven repository, and it is never bundled.
    "compileOnly"(files(rootProject.file("libs/ae2/ae2-19.2.18.jar")))
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withJavadocJar()
    withSourcesJar()
}

minecraft {
    accessTransformers {
        file("src/main/resources/META-INF/accesstransformer.cfg")
    }
}

runs {
    create("client") {
        runType("client")
        systemProperty("forge.logging.markers", "SCAN,REGISTRIES,REGISTRYDUMP")
        systemProperty("forge.logging.console.level", "debug")
        workingDirectory(project.file("run"))
        modSources(sourceSets["main"])
        dependencies {
            runtime(apiClasspath)
        }
    }
    create("server") {
        runType("server")
        systemProperty("forge.logging.markers", "SCAN,REGISTRIES,REGISTRYDUMP")
        systemProperty("forge.logging.console.level", "debug")
        workingDirectory(project.file("run"))
        modSources(sourceSets["main"])
        dependencies {
            runtime(apiClasspath)
        }
    }
}

subsystems {
    parchment {
        minecraftVersion = libs.versions.parchment.minecraft.get()
        mappingsVersion = libs.versions.parchment.mappings.get()
        addRepository = false
    }
    decompiler {
        maxMemory("3G")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
    options.isDeprecation = true
    val disabledLint = listOf("processing", "path", "fallthrough", "serial", "overloads", "this-escape")
    options.compilerArgs.addAll(listOf("-Xlint:all") + disabledLint.map { "-Xlint:-$it" })
    options.compilerArgs.addAll(listOf("-parameters"))
}

configure<BasePluginExtension> {
    archivesName.set("${archivesName.get()}-mc$minecraftVersion")
}

tasks.named<Jar>("jar") {
    val version = rootProject.ext["internalVersion"]
    inputs.property("version", version)
    manifest.attributes(
        "Implementation-Version" to version,
        "WorldEdit-Version" to version,
        "WorldEdit-Kind" to "Mod"
    )
}

// Development speed: docs and source jars are not part of the installed mod.
tasks.matching { it.name == "javadoc" || it.name == "sourcesJar" }.configureEach {
    enabled = false
}

tasks.named<Copy>("processResources") {
    // These values are baked into the mod metadata, so redo the task when they change.
    val properties = mapOf(
        "version" to rootProject.ext["internalVersion"],
        "neoVersion" to libs.neoforge.get().version,
        "minecraftVersion" to minecraftVersion,
        "nextMajorMinecraftVersion" to nextMajorMinecraftVersion
    )
    properties.forEach { (key, value) -> inputs.property(key, value) }

    filesMatching("META-INF/neoforge.mods.toml") {
        expand(properties)
    }

    // WorldEdit's own resources (language files, defaults) ship inside the mod.
    from(project(":worldedit-core").tasks.named("processResources"))
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("dist")

    relocate("com.sk89q.jchronic", "com.sk89q.worldedit.jchronic")
    relocate("org.antlr.v4", "com.sk89q.worldedit.antlr4")
    relocate("net.royawesome.jlibnoise", "com.sk89q.worldedit.jlibnoise")
    relocate("net.kyori.text", "com.sk89q.worldedit.util.formatting.text")
    relocate("net.kyori.minecraft", "com.sk89q.worldedit.util.kyori")

    dependencies {
        include(project(":worldedit-libs:core"))
        include(project(":worldedit-core"))
        include(dependency("com.sk89q:jchronic"))
        include(dependency("org.antlr:antlr4-runtime"))
        include(dependency("org.mozilla:rhino-runtime"))
        include(dependency("com.sk89q.lib:jlibnoise"))
        exclude(dependency("com.google.code.findbugs:jsr305"))
    }

    exclude("GradleStart**")
    exclude(".cache")
    exclude("LICENSE*")
    exclude("META-INF/maven/**")

    minimize {
        exclude(dependency("com.sk89q:jchronic"))
        exclude(dependency("org.mozilla:rhino-runtime"))
    }
}

tasks.named("assemble") {
    dependsOn("shadowJar")
}
