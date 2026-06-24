import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.4.0"
    application
}

val aisdkVersion = providers.gradleProperty("aisdkVersion").orElse("0.3.0-beta01")

dependencies {
    implementation("ai.torad:torad-aisdk:${aisdkVersion.get()}")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("ai.torad.aisdk.smoke.ReadmeSampleSmokeKt")
}
