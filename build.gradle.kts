plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.ktor)
    kotlin("plugin.serialization") version libs.versions.kotlin
}

group = "tywinlanni.github.com"
version = "0.0.6"

application {
    mainClass.set("tywinlanni.github.com.plankaTelegram.MainKt")
}

kotlin {
    jvmToolchain(17)
}

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    implementation(libs.ktor.client.core.jvm)
    implementation(libs.ktor.client.cio.jvm)
    implementation(libs.ktor.client.auth)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.serialization)

    implementation(libs.logback.classic)

    implementation(libs.telegram.bot)

    implementation(libs.mongodb.driver.kotlin.coroutine)
    implementation(libs.mongodb.bson.kotlinx)

    testImplementation(libs.kotlin.test.junit)
}

tasks.jar {
    manifest.attributes["Main-Class"] = "tywinlanni.github.com.plankaTelegram.MainKt"
    val dependencies = configurations
        .runtimeClasspath
        .get()
        .map(::zipTree)
    from(dependencies)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}