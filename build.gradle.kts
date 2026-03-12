plugins {
    kotlin("jvm") version "2.3.10"
    kotlin("plugin.serialization") version "2.3.10"
    id("com.gradleup.shadow") version "8.3.6"
    application
}

group = "com.lutrinecreations"
version = "2.0.0"

val jdaVersion = "6.3.1"
val openAiKotlinVersion = "4.1.0"
val ktorVersion = "3.1.1"
val coroutinesVersion = "1.10.1"

// libdave-jvm uses commit-hash-based snapshot versions on the Lavalink repo.
// Check https://github.com/KyokoBot/libdave-jvm for the latest commit hash
// and use its first 9 characters as the version, e.g. "a1b2c3d4e-SNAPSHOT".
val libdaveVersion = "0.1.2"

repositories {
    mavenCentral()
    maven("https://maven.lavalink.dev/snapshots")
}

dependencies {
    // Discord
    implementation("net.dv8tion:JDA:$jdaVersion")

    // DAVE protocol (Discord Audio & Video End-to-End Encryption)
    implementation("moe.kyokobot.libdave:adapter-jda:$libdaveVersion")
    implementation("moe.kyokobot.libdave:impl-jni:$libdaveVersion")
    // Native library for your deployment platform — pick the one(s) you need:
    // Docker Alpine = musl, most cloud VMs = glibc
    runtimeOnly("moe.kyokobot.libdave:natives-linux-musl-x86-64:$libdaveVersion")
    runtimeOnly("moe.kyokobot.libdave:natives-linux-x86-64:$libdaveVersion")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")

    // OpenAI Kotlin client (for non-streaming TTS calls and future API usage)
    implementation("com.aallam.openai:openai-client:$openAiKotlinVersion")

    // Ktor client (used as the HTTP engine for openai-kotlin AND for streaming TTS)
    runtimeOnly("io.ktor:ktor-client-okhttp:$ktorVersion")
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    // Serialization (replaces Gson)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")

    // Logging (JDA uses SLF4J; this provides an implementation)
    implementation("ch.qos.logback:logback-classic:1.5.32")

    // Testing
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.lutrinecreations.MainKt")
}

tasks.shadowJar {
    archiveFileName.set("LutrineTTS.jar")
    mergeServiceFiles()
}