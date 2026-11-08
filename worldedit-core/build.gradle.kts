plugins {
    `java-library`
    antlr
    checkstyle
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withJavadocJar()
    withSourcesJar()
}

configurations {
    register("languageFiles")
}

dependencies {
    constraints {
        "implementation"(libs.snakeyaml) {
            because("Minecraft provides SnakeYaml")
        }
    }

    // Relocated text/NBT libraries, built by :worldedit-libs:core. The plain project
    // dependency would only expose an empty class directory, so take the shaded jar.
    "api"(project(mapOf("path" to ":worldedit-libs:core", "configuration" to "shaded")))

    "compileOnly"(libs.trueZip)
    "implementation"(libs.rhino)
    "implementation"(libs.snakeyaml)
    "implementation"(libs.guava)
    "compileOnlyApi"(libs.jsr305)
    "implementation"(libs.gson)

    "implementation"(libs.jchronic) {
        exclude(group = "junit", module = "junit")
    }
    "implementation"(libs.jlibnoise)
    "implementation"(platform(libs.log4j.bom))
    "implementation"(libs.log4j.api)
    "implementation"(libs.fastutil)

    "antlr"(libs.antlr4)
    "implementation"(libs.antlr4.runtime)

    val libsAp = project(mapOf("path" to ":worldedit-libs:core:ap", "configuration" to "shaded"))
    "compileOnly"(libsAp)
    "annotationProcessor"(libsAp)
    "annotationProcessor"(libs.guava)
    "compileOnly"(libs.autoValue.annotations)
    "annotationProcessor"(libs.autoValue)

    "compileOnly"(libs.autoService) {
        because("Needed to resolve annotations in Piston")
    }
    "compileOnly"(libs.jetbrains.annotations) {
        because("Needed to resolve annotations in lin-bus")
    }
    "testCompileOnly"(libs.jetbrains.annotations) {
        because("Needed to resolve annotations in lin-bus")
    }

    "languageFiles"(
        "${project.group}:worldedit-lang:${libs.versions.lang.worldeditBase.get()}:${libs.versions.lang.version.get()}@zip"
    )

    "testImplementation"(platform(libs.junit.bom))
    "testImplementation"(libs.junit.jupiter.api)
    "testImplementation"(libs.junit.jupiter.params)
    "testImplementation"(libs.jqwik)
    "testImplementation"(platform(libs.mockito.bom))
    "testImplementation"(libs.mockito.core)
    "testImplementation"(libs.mockito.junit.jupiter)
    "testRuntimeOnly"(libs.junit.jupiter.engine)
    "testRuntimeOnly"(libs.log4j.core)
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
    options.isDeprecation = true
    val disabledLint = listOf("processing", "path", "fallthrough", "serial", "overloads", "this-escape")
    options.compilerArgs.addAll(listOf("-Xlint:all") + disabledLint.map { "-Xlint:-$it" })
    options.compilerArgs.addAll(listOf("-parameters", "-Werror"))
}

tasks.compileJava {
    dependsOn(":worldedit-libs:build")
    options.compilerArgs.add("-Aarg.name.key.prefix=")
}

tasks.generateGrammarSource {
    val pkg = "com.sk89q.worldedit.antlr"
    outputDirectory = file("build/generated-src/antlr/main/${pkg.replace('.', '/')}")
    arguments = listOf("-visitor", "-package", pkg, "-Xexact-output-dir")
}

tasks.test {
    useJUnitPlatform {
        includeEngines("junit-jupiter", "jqwik")
    }
    maxHeapSize = "1G"
}

configure<CheckstyleExtension> {
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    toolVersion = "10.16.0"
}
tasks.withType<Checkstyle>().configureEach {
    exclude("com/sk89q/worldedit/antlr/**/*.java")
}

sourceSets.named("main") {
    java {
        srcDir("src/legacy/java")
    }
}

tasks.named<Copy>("processResources") {
    from(configurations.named("languageFiles")) {
        rename { "i18n.zip" }
        into("lang")
    }
}

tasks.named("check") {
    dependsOn("checkstyleMain", "checkstyleTest")
}
