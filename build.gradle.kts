import org.gradle.api.publish.maven.MavenPublication
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
    signing
}

group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION_NAME").get()

kotlin {
    jvmToolchain(21)

    android {
        namespace = "ai.torad.aisdk"
        compileSdk = libs.versions.android.compile.sdk.get().toInt()
        minSdk = libs.versions.android.min.sdk.get().toInt()
        withHostTest {}
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "AiSdk"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
            api(libs.ktor.client.core)
            api(libs.ktor.client.mock)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.turbine)
        }
    }
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/torad-labs/aisdk-kotlin")
            credentials {
                username = providers.environmentVariable("GITHUB_ACTOR").orNull
                password = providers.environmentVariable("GITHUB_TOKEN").orNull
            }
        }
    }

    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set(providers.gradleProperty("POM_NAME"))
            description.set(providers.gradleProperty("POM_DESCRIPTION"))
            url.set(providers.gradleProperty("POM_URL"))
            inceptionYear.set(providers.gradleProperty("POM_INCEPTION_YEAR"))
            licenses {
                license {
                    name.set(providers.gradleProperty("POM_LICENSE_NAME"))
                    url.set(providers.gradleProperty("POM_LICENSE_URL"))
                    distribution.set(providers.gradleProperty("POM_LICENSE_DIST"))
                }
            }
            scm {
                url.set(providers.gradleProperty("POM_SCM_URL"))
                connection.set(providers.gradleProperty("POM_SCM_CONNECTION"))
                developerConnection.set(providers.gradleProperty("POM_SCM_DEV_CONNECTION"))
            }
            developers {
                developer {
                    id.set(providers.gradleProperty("POM_DEVELOPER_ID"))
                    name.set(providers.gradleProperty("POM_DEVELOPER_NAME"))
                }
            }
        }
    }
}

val signingKey = providers.environmentVariable("SIGNING_KEY")
val signingPassword = providers.environmentVariable("SIGNING_PASSWORD")

signing {
    isRequired = signingKey.isPresent && signingPassword.isPresent
    if (isRequired) {
        useInMemoryPgpKeys(signingKey.get(), signingPassword.get())
        sign(publishing.publications)
    }
}
