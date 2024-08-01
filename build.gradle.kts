plugins {
    kotlin("jvm") version "1.9.23"
}

group = "com.lutrinecreations"
version = "1.0-SNAPSHOT"

val jdaVersion = "5.0.1"
val lavaplayerVersion = "2.2.1"
val openAiKotlinVersion = "3.8.2"

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("net.dv8tion:JDA:$jdaVersion")
    implementation("com.aallam.openai:openai-client:$openAiKotlinVersion")
    implementation("com.google.code.gson:gson:2.10.1")
    runtimeOnly("io.ktor:ktor-client-okhttp:2.3.11")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}