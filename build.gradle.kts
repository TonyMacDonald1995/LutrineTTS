import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.23"
    application
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
    jvmToolchain(8)
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

tasks.jar {
    archiveFileName.set("LutrineTTS.jar")
    manifest {
        attributes["Main-Class"] = "com.lutrinecreations.MainKt"
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    val contents = configurations.runtimeClasspath.get().map {
        if (it.isDirectory)
            it
        else
            zipTree(it)
    } + sourceSets.main.get().output

    from(contents)
}

application {
    mainClass.set("com.lutrinecreations.Main")
}