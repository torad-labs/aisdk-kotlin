import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.DetektCreateBaselineTask
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
    alias(libs.plugins.dokka)
    `maven-publish`
    signing
}

group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION_NAME").get()

kotlin {
    jvmToolchain(21)

    // Phase 3 (Kotlin modernization): warn — don't fail — on missing visibility
    // modifiers / explicit return types across the public surface. This sizes the
    // backlog for the later strict `explicitApi()` pass; it is compile-time-only
    // and emits `w:` warnings without breaking the build.
    explicitApiWarning()

    // KMP per-target + umbrella sources jars, attached to the publications
    // (Maven Central requires a -sources.jar alongside each artifact).
    withSourcesJar(publish = true)

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

    val xcf = XCFramework("AiSdk")
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "AiSdk"
            isStatic = true
            binaryOption("bundleId", "ai.torad.aisdk")
            xcf.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
            api(libs.ktor.client.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.turbine)
        }
    }
}

// --- Static analysis: detekt (1.23.x) + ktlint-backed formatting rules ---
// Runs WITHOUT type resolution: detekt 1.23.x bundles an older Kotlin front-end and
// cannot resolve types for KMP non-JVM source sets anyway (detekt#5961), and detekt 2.0
// is config-cache-incompatible + KMP-variant-exploding (detekt#8882). Style/formatting
// rules are compiler-version-agnostic, so we point detekt straight at the KMP source dirs.
detekt {
    buildUponDefaultConfig = true
    parallel = true
    baseline = file("$projectDir/detekt-baseline.xml")
    source.setFrom(
        "src/commonMain/kotlin",
        "src/jvmMain/kotlin",
        "src/androidMain/kotlin",
        "src/iosMain/kotlin",
        "src/commonTest/kotlin",
    )
}

dependencies {
    detektPlugins(libs.detekt.formatting)
}

tasks.withType<Detekt>().configureEach {
    jvmTarget = JvmTarget.JVM_17.target
    reports {
        html.required.set(true)
        xml.required.set(true)
        sarif.required.set(false)
        md.required.set(false)
    }
}

tasks.withType<DetektCreateBaselineTask>().configureEach {
    jvmTarget = JvmTarget.JVM_17.target
}

// --- Coverage: Kover (0.9.x). Measures the JVM target; excludes generated serializers. ---
kover {
    reports {
        filters {
            excludes {
                classes("*\$\$serializer")
            }
        }
        // Measurement only for now — no enforced threshold so the build is not gated.
    }
}

// --- API docs: Dokka 2.x. HTML output (`./gradlew dokkaGenerate` → build/dokka/html). ---
// Note: Dokka's *Javadoc* format does not support KMP projects by design (it models Kotlin
// as consumed from Java, which is meaningless for Kotlin/Native and Kotlin/JS — Kotlin/dokka#1753).
// For the Maven Central javadoc-jar requirement we therefore package the HTML output, which
// Sonatype accepts (the jar contents are arbitrary). Wired but not added to any publication yet —
// the publication itself is finalized in the later publishing pass.
dokka {
    moduleName.set(providers.gradleProperty("POM_NAME"))
}

val dokkaJavadocJar by tasks.registering(Jar::class) {
    description = "Assembles a javadoc-classifier jar from the Dokka HTML output (Maven Central requirement)."
    group = JavaBasePlugin.DOCUMENTATION_GROUP
    dependsOn(tasks.named("dokkaGeneratePublicationHtml"))
    from(layout.buildDirectory.dir("dokka/html"))
    archiveClassifier.set("javadoc")
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
        // Maven Central requires a -javadoc.jar per artifact. Dokka's Javadoc format
        // doesn't support KMP, so dokkaJavadocJar packages the HTML output instead
        // (Sonatype accepts arbitrary jar contents for the javadoc classifier).
        artifact(dokkaJavadocJar)
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
val publishingToRemoteRepository = providers.provider {
    gradle.startParameter.taskNames.any { taskName ->
        taskName.startsWith("publish") && !taskName.contains("ToMavenLocal")
    }
}

signing {
    isRequired = publishingToRemoteRepository.get()
    if (signingKey.isPresent && signingPassword.isPresent) {
        useInMemoryPgpKeys(signingKey.get(), signingPassword.get())
    }
    sign(publishing.publications)
}

tasks.withType<PublishToMavenRepository>().configureEach {
    doFirst {
        require(!version.toString().endsWith("-SNAPSHOT")) {
            "Remote publication requires a stable VERSION_NAME; refusing to publish SNAPSHOT version $version."
        }
        require(signingKey.isPresent && signingPassword.isPresent) {
            "Remote publication requires SIGNING_KEY and SIGNING_PASSWORD so artifacts cannot be published unsigned."
        }
        require(
            providers.environmentVariable("GITHUB_ACTOR").isPresent &&
                providers.environmentVariable("GITHUB_TOKEN").isPresent,
        ) {
            "Remote publication requires GITHUB_ACTOR and GITHUB_TOKEN for GitHub Packages credentials."
        }
    }
}
