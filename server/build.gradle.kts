import org.gradle.process.CommandLineArgumentProvider
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ktor)
}

application {
    mainClass = "dev.kolektiv.kalendee.ApplicationKt"
}

dependencies {
    api(project(":core"))
    implementation(libs.logback)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    implementation(libs.ktor.serverContentNegotiation)
    implementation(libs.ktor.serverStatusPages)
    implementation(libs.ktor.serializationKotlinxJson)
    implementation(libs.ktor.clientCio)
    implementation(libs.angus.mail)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.postgresql)
    implementation(libs.hikari)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.koin.ktor)
    implementation(libs.koin.logger.slf4j)
    implementation(libs.password4j)
    implementation(libs.keel.ktor)
    implementation(libs.aws.sdk.s3)
    implementation(libs.kord.rest)
    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.ktor.clientContentNegotiation)
    testImplementation(libs.ktor.clientMock)
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.h2)
}

val packDir = layout.projectDirectory.dir("pack")
val packDist = packDir.dir("dist")
val compileKotlin = tasks.named<KotlinCompile>("compileKotlin")
val compileOutput = compileKotlin.flatMap { it.destinationDirectory }

val generateKeelTypes = tasks.register<JavaExec>("generateKeelTypes") {
    group = "build"
    description = "Generate TypeScript types from @KeelType and @KeelAction."
    dependsOn(compileKotlin)
    classpath = files(compileOutput, configurations.named("runtimeClasspath"))
    mainClass.set("dev.kolektiv.keel.typegen.TypegenCliKt")
    val tsOut = packDir.file("src/lib/page-types.ts")
    val jsonOut = packDir.file("src/lib/page-types.json")
    argumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "--output",
                tsOut.asFile.absolutePath,
                "--pages-name",
                "KalendeePages",
                "--format",
                "ts",
                "--emit-json",
                jsonOut.asFile.absolutePath,
                "--package",
                "dev.kolektiv.kalendee.web",
            )
        },
    )
    inputs.files(compileOutput)
    outputs.file(tsOut)
    outputs.file(jsonOut)
}

val packInstall = tasks.register<Exec>("packInstall") {
    group = "build"
    description = "Install the Kalendee Svelte pack dependencies."
    workingDir = packDir.asFile
    commandLine("pnpm", "install", "--frozen-lockfile")
    inputs.files(
        packDir.file("package.json"),
        packDir.file("pnpm-lock.yaml"),
        packDir.file(".npmrc"),
        packDir.file("pnpm-workspace.yaml"),
    )
    outputs.dir(packDir.dir("node_modules"))
}

val buildPack = tasks.register<Exec>("buildPack") {
    group = "build"
    description = "Bundle the Kalendee Svelte pack into dist/kalendee.feb."
    dependsOn(generateKeelTypes, packInstall)
    workingDir = packDir.asFile
    commandLine("pnpm", "build")
    inputs.dir(packDir.dir("src"))
    inputs.files(
        packDir.file("package.json"),
        packDir.file("vite.config.ts"),
        packDir.file("svelte.config.js"),
        packDir.file("tsconfig.json"),
    )
    outputs.dir(packDist)
}

tasks.named<Copy>("processResources") {
    dependsOn(buildPack)
    from(packDist) {
        include("kalendee.feb")
        into("keel")
    }
}

tasks.named<JavaExec>("run") {
    dependsOn(buildPack)
    val envFile = rootProject.layout.projectDirectory.file(".env.local")
    inputs.file(envFile).optional()
    doFirst {
        val file = envFile.asFile
        if (!file.exists()) return@doFirst
        file.readLines().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val body = line.removePrefix("export ").trim()
            val eq = body.indexOf('=')
            if (eq <= 0) return@forEach
            val key = body.take(eq).trim()
            var value = body.substring(eq + 1).trim()
            if (value.length >= 2) {
                val quote = value.first()
                if ((quote == '"' || quote == '\'') && value.last() == quote) {
                    value = value.substring(1, value.length - 1)
                }
            }
            if (key.isNotEmpty() && !environment.containsKey(key) && System.getenv(key) == null) {
                environment(key, value)
            }
        }
    }
}

tasks.named("jar") {
    dependsOn(buildPack)
}
