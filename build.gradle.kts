val ktor_version: String by project
val kotlin_version: String by project
val logback_version: String by project
val kmongo_version: String by project
val telegram_bot_version: String by project

plugins {
    kotlin("jvm") version "1.9.0"
    kotlin("plugin.serialization") version "1.9.0"
    id("io.ktor.plugin") version "2.3.3"
}

group = "tywinlanni.github.com"
version = "0.0.4"

application {
    mainClass.set("tywinlanni.github.com.plankaTelegram.MainKt")
}

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    implementation("io.ktor:ktor-client-core-jvm")
    implementation("io.ktor:ktor-client-cio-jvm")
    implementation("io.ktor:ktor-client-auth")
    implementation("io.ktor:ktor-client-logging")
    implementation("io.ktor:ktor-client-content-negotiation")
    implementation("io.ktor:ktor-serialization-kotlinx-json")
    implementation("io.ktor:ktor-client-serialization")

    implementation("ch.qos.logback:logback-classic:$logback_version")

    implementation("io.github.kotlin-telegram-bot.kotlin-telegram-bot:telegram:$telegram_bot_version")

    implementation("org.litote.kmongo:kmongo-coroutine:$kmongo_version")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlin_version")
}
