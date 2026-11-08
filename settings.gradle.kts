pluginManagement {
    repositories {
        gradlePluginPortal()
        maven {
            name = "EngineHub"
            url = uri("https://maven.enginehub.org/repo/")
        }
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven {
            name = "EngineHub"
            url = uri("https://maven.enginehub.org/repo/")
        }
        ivy {
            url = uri("https://repo.enginehub.org/language-files/")
            name = "EngineHub Language Files"
            patternLayout {
                artifact("[organisation]/[module]/[revision]/[artifact]-[revision](+[classifier])(.[ext])")
                setM2compatible(true)
            }
            metadataSources {
                artifact()
            }
            content {
                includeModuleByRegex(".*", "worldedit-lang")
            }
        }
        gradle.settingsEvaluated {
            // Duplicates repositoriesHelper.kt, since we can't import it
            val allowedPrefixes = listOf(
                "https://maven.enginehub.org",
                "https://repo.maven.apache.org/maven2/",
                "https://maven.neoforged.net",
                "https://maven.fabricmc.net",
                "https://libraries.minecraft.net",
                "file:"
            )

            for (repo in this@repositories) {
                if (repo is MavenArtifactRepository) {
                    val urlString = repo.url.toString()
                    check(allowedPrefixes.any { urlString.startsWith(it) }) {
                        "Only EngineHub/Central repositories are allowed: ${repo.url} found"
                    }
                }
            }
        }
    }
}

logger.lifecycle("""
*******************************************
 You are building WorldEdit!

 If you encounter trouble:
 1) Read COMPILING.md if you haven't yet
 2) Try running 'build' in a separate Gradle run
 3) Use gradlew and not gradle
 4) If you still need help, ask on Discord! https://discord.gg/enginehub

 Output files will be in [subproject]/build/libs
*******************************************
""")

rootProject.name = "worldedit"



include("worldedit-libs")

// SurvivalEdit only ships a NeoForge build. The other platform modules live in attic/.
include("worldedit-core")
include("worldedit-libs:core")
include("worldedit-neoforge")
include("worldedit-libs:core:ap")
