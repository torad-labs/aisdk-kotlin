import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.DetektCreateBaselineTask
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
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

    // Phase 3 (Kotlin modernization): STRICT explicit API mode — every public
    // declaration must carry an explicit visibility modifier and an explicit
    // return type, or compilation fails. This is the 1.0-gate guarantee that the
    // published surface is intentional (KEEP-0045: compile-time-only, no bytecode
    // or semantic change). The non-API plumbing has been `internal`-ized so the
    // remaining `public` surface is the supported SDK contract.
    explicitApi()

    // Phase 3 (Kotlin modernization): built-in KGP binary-compatibility (ABI)
    // validation. Now that explicitApi() + `internal`-ization make the public
    // surface intentional, freeze it against a committed golden dump. The dump
    // (`api/jvm/torad-aisdk.api` for the JVM ABI + `api/torad-aisdk.klib.api`
    // for the merged klib ABI) is the supported 1.0 contract; `checkKotlinAbi`
    // (wired under `check`) fails the build on any unreviewed surface change,
    // `updateKotlinAbi` regenerates the dump after an intentional change. (The
    // `*LegacyAbi` task aliases work too but are deprecated in 2.3.x.)
    //
    // The DSL is Experimental, hence the opt-in. `keepLocallyUnsupportedTargets`
    // lets a host without the Apple toolchain (Linux CI) infer the iOS klib ABI
    // from the dump instead of failing, so the check is reproducible everywhere.
    //
    // `@InternalAiSdkApi` is fed into the exclusion filter: declarations that are
    // technically `public` for KMP/inlining reasons but marked internal-contract
    // are kept OUT of the frozen surface, so churning them never trips the check.
    // (Today nothing carries that annotation — the internal plumbing uses the
    // `internal` keyword, which is already absent from the ABI — so this is a
    // forward-looking guard rather than an active exclusion.)
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation {
        // Kotlin 2.4.0 streamlined this DSL: applying the `abiValidation {}` block
        // enables validation (the redundant `enabled` flag was removed), the
        // `klib {}` wrapper was removed (klib dumps are now always generated), and
        // `klib.keepUnsupportedTargets` was renamed to the top-level
        // `keepLocallyUnsupportedTargets`. `true` lets a host without the Apple
        // toolchain (Linux CI) infer the iOS klib ABI from the committed dump.
        keepLocallyUnsupportedTargets.set(true)
        filters.exclude.annotatedWith.add("ai.torad.aisdk.InternalAiSdkApi")
    }

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

    // Linux/Native target. Apple targets need macOS only to LINK+RUN their
    // SDK/simulator binaries (an Apple-tooling constraint); the Kotlin/Native
    // *runtime* (coroutines, Flow emission-context rules, the shared
    // kotlinx/ktor code) is identical across Native targets. linuxX64 compiles
    // AND runs on a Linux host, so Native-general behaviour is verified in the
    // cheap `check` leg, not only the macOS one — and ships a Linux/Native
    // artifact for server-side Kotlin/Native and CLI consumers.
    linuxX64()

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
    config.setFrom(file("$projectDir/detekt.yml"))
    baseline = file("$projectDir/detekt-baseline.xml")
    source.setFrom(
        "src/commonMain/kotlin",
        "src/jvmMain/kotlin",
        "src/androidMain/kotlin",
        "src/nativeMain/kotlin",
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
        // Local staging directory: zipped and uploaded to the Central Portal API in CI.
        maven {
            name = "LocalStaging"
            url = uri(layout.buildDirectory.dir("staging-deploy"))
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

// KMP sign tasks for different platforms all write to the shared build/libs/ directory.
// Without explicit ordering Gradle detects implicit cross-publication dependencies and fails.
// mustRunAfter (not dependsOn) keeps sign tasks optional when not publishing.
tasks.withType<PublishToMavenRepository>().configureEach {
    mustRunAfter(tasks.withType<Sign>())
}

tasks.withType<PublishToMavenRepository>().configureEach {
    doFirst {
        val repoName = repository.name
        require(!version.toString().endsWith("-SNAPSHOT")) {
            "Publication to $repoName requires a stable VERSION_NAME; refusing to publish SNAPSHOT version $version."
        }
        require(signingKey.isPresent && signingPassword.isPresent) {
            "Publication to $repoName requires SIGNING_KEY and SIGNING_PASSWORD so artifacts cannot be published unsigned."
        }
        if (repoName == "GitHubPackages") {
            require(
                providers.environmentVariable("GITHUB_ACTOR").isPresent &&
                    providers.environmentVariable("GITHUB_TOKEN").isPresent,
            ) {
                "Publication to GitHubPackages requires GITHUB_ACTOR and GITHUB_TOKEN credentials."
            }
        }
    }
}
