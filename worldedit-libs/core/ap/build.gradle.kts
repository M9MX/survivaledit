import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

// Piston's annotation processor, relocated the same way as the other bundled libraries.
plugins {
    `java-library`
    alias(libs.plugins.shadow)
}

configurations {
    create("shade")
}

val relocations: Map<String, String> = mapOf(
    "net.kyori.text" to "com.sk89q.worldedit.util.formatting.text",
    "net.kyori.minecraft" to "com.sk89q.worldedit.util.kyori",
)

tasks.named<ShadowJar>("shadowJar") {
    configurations = listOf(project.configurations["shade"])
    archiveClassifier.set("")

    exclude("module-info.class")
    dependencies {
        exclude(dependency("com.google.guava:guava"))
        exclude(dependency("com.google.code.gson:gson"))
        exclude(dependency("com.google.errorprone:error_prone_annotations"))
        exclude(dependency("com.google.guava:failureaccess"))
        exclude(dependency("org.checkerframework:checker-qual"))
        exclude(dependency("org.jetbrains:annotations"))
        exclude(dependency("org.apache.logging.log4j:log4j-api"))
        exclude(dependency("com.google.code.findbugs:jsr305"))
        exclude { it.moduleGroup == "org.jetbrains.kotlin" }
    }

    relocations.forEach { (from, to) -> relocate(from, to) }
}

dependencies {
    "shade"(libs.piston.coreAp.annotations)
    "shade"(libs.piston.coreAp.processor)
}

// The shaded jar is the only artifact this project has to offer: it already contains every
// library, relocated. Consumers ask for it by name so they never see the empty classes dir.
tasks.named<Jar>("jar") {
    enabled = false
}

val shaded: Configuration by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}
artifacts {
    add(shaded.name, tasks.named("shadowJar"))
}

tasks.named("assemble") {
    dependsOn("shadowJar")
}
