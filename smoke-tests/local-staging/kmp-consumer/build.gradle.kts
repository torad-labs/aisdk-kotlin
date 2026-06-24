import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
}

val aisdkVersion = providers.gradleProperty("aisdkVersion").orElse("0.3.0-beta01")

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("ai.torad:torad-aisdk:${aisdkVersion.get()}")
        }
    }
}
