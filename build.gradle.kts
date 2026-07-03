import kotlinx.kover.gradle.plugin.dsl.AggregationType
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.Classpath
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.process.CommandLineArgumentProvider
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.poko)
    alias(libs.plugins.kover)
    alias(libs.plugins.dokka)
    `maven-publish`
    signing
}

group = providers.gradleProperty("GROUP").get()
val releaseVersion = providers.gradleProperty("VERSION_NAME").get()
val snapshotPublishEnabled = providers.environmentVariable("SNAPSHOT_PUBLISH").map {
    it == "1" || it.equals("true", ignoreCase = true)
}.orElse(false)
version = if (snapshotPublishEnabled.get()) {
    providers.environmentVariable("SNAPSHOT_VERSION").orNull
        ?: "${releaseVersion.removeSuffix("-SNAPSHOT")}-SNAPSHOT"
} else {
    releaseVersion
}

val detektCliRuntime by configurations.creating
val detektPluginClasspath by configurations.creating

abstract class DetektPluginsArgumentProvider : CommandLineArgumentProvider {
    @get:Classpath
    abstract val pluginClasspath: ConfigurableFileCollection

    override fun asArguments(): Iterable<String> =
        listOf("--plugins", pluginClasspath.files.joinToString(",") { it.absolutePath })
}

kotlin {
    jvmToolchain(21)

    compilerOptions {
        languageVersion.set(KotlinVersion.KOTLIN_2_3)
        apiVersion.set(KotlinVersion.KOTLIN_2_3)
    }

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
            jvmDefault.set(JvmDefaultMode.ENABLE)
            freeCompilerArgs.add("-Xjvm-expose-boxed")
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            jvmDefault.set(JvmDefaultMode.ENABLE)
            freeCompilerArgs.add("-Xjvm-expose-boxed")
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

    // Apply the default source-set hierarchy explicitly: declaring the manual
    // jvmAndAndroidMain dependsOn edge below otherwise disables Kotlin's auto-applied
    // template, which is what groups the Native targets (linux + ios) under nativeMain.
    // With it explicit, nativeMain stays wired AND the custom jvmAndAndroid group is additive.
    applyDefaultHierarchyTemplate()

    sourceSets {
        // Intermediate source set shared by the JVM and Android targets: both are
        // JVM-backed, so their `actual`s (SecureRandom via java.security, the
        // ProcessBuilder-backed MCP stdio transport) are identical. Declaring them
        // once here — instead of byte-identical copies in jvmMain + androidMain —
        // makes it a single source of truth.
        val jvmAndAndroidMain by creating { dependsOn(commonMain.get()) }
        jvmMain { dependsOn(jvmAndAndroidMain) }
        androidMain { dependsOn(jvmAndAndroidMain) }

        commonMain.dependencies {
            // Keep JVM/Android consumers on the oldest stdlib accepted by our public
            // dependency floor; klib consumers still need this artifact's producer KGP.
            api(kotlin("stdlib", "2.3.21"))
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
        // Konsist architecture tests run on the JVM only (Konsist is a JVM library that scans
        // the project's .kt source from disk). They enforce the whole-codebase structural
        // tenets that single-file lints can't see (e.g. "every data class `…Event` has a supertype").
        jvmTest.dependencies {
            implementation(libs.ktor.client.cio)
            implementation(libs.konsist)
        }
    }
}

tasks.withType<org.gradle.api.tasks.compile.JavaCompile>().configureEach {
    options.release.set(17)
}

dependencies {
    detektCliRuntime(libs.detekt.cli)
    detektPluginClasspath(libs.detekt.formatting)
    // The project's own architectural tenets as detekt rules (mirrors of the ast-grep
    // PreToolUse hook rules), so they surface in the IDE + `check` for every developer.
    detektPluginClasspath(project(":detekt-rules"))
}

// --- Static analysis: detekt (1.23.x) + ktlint-backed formatting rules ---
// Runs WITHOUT type resolution: detekt 1.23.x bundles an older Kotlin front-end and
// cannot resolve types for KMP non-JVM source sets anyway (detekt#5961), and detekt 2.0
// is still alpha. Style/formatting rules are compiler-version-agnostic, so we point
// detekt straight at the KMP source dirs. This uses the stable CLI instead of the 1.x
// Gradle plugin because the plugin calls a Gradle API removed in Gradle 10.
val detektSourceDirs = listOf(
    "src/commonMain/kotlin",
    "src/jvmAndAndroidMain/kotlin",
    "src/jvmMain/kotlin",
    "src/androidMain/kotlin",
    "src/nativeMain/kotlin",
    "src/commonTest/kotlin",
)
val detektExistingSourceDirs = detektSourceDirs
    .map { layout.projectDirectory.dir(it).asFile }
    .filter { it.isDirectory }
val detektConfigFile = layout.projectDirectory.file("detekt.yml")
val detektBaselineFile = layout.projectDirectory.file("detekt-baseline.xml")
val detektHtmlReport = layout.buildDirectory.file("reports/detekt/detekt.html")
val detektXmlReport = layout.buildDirectory.file("reports/detekt/detekt.xml")
val detektPluginArgumentProvider = objects.newInstance<DetektPluginsArgumentProvider>().apply {
    pluginClasspath.from(detektPluginClasspath)
}

val detektCliArguments = listOf(
    "--build-upon-default-config",
    "--parallel",
    "--jvm-target",
    JvmTarget.JVM_17.target,
    "--config",
    detektConfigFile.asFile.absolutePath,
    "--baseline",
    detektBaselineFile.asFile.absolutePath,
    "--input",
    detektExistingSourceDirs.joinToString(",") { it.absolutePath },
    "--report",
    "html:${detektHtmlReport.get().asFile.absolutePath}",
    "--report",
    "xml:${detektXmlReport.get().asFile.absolutePath}",
)

val detekt by tasks.registering(JavaExec::class) {
    description = "Runs detekt over the Kotlin source tree."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(":detekt-rules:jar")
    mainClass.set("io.gitlab.arturbosch.detekt.cli.Main")
    classpath = detektCliRuntime
    args(detektCliArguments)
    argumentProviders.add(detektPluginArgumentProvider)
    inputs.files(detektExistingSourceDirs).withPropertyName("sources")
    inputs.file(detektConfigFile).withPropertyName("config")
    inputs.file(detektBaselineFile).withPropertyName("baseline")
    inputs.files(detektPluginClasspath).withPropertyName("plugins").withNormalizer(ClasspathNormalizer::class)
    outputs.file(detektHtmlReport).withPropertyName("htmlReport")
    outputs.file(detektXmlReport).withPropertyName("xmlReport")
}

val detektBaseline by tasks.registering(JavaExec::class) {
    description = "Regenerates detekt-baseline.xml using the stable detekt CLI."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(":detekt-rules:jar")
    mainClass.set("io.gitlab.arturbosch.detekt.cli.Main")
    classpath = detektCliRuntime
    args(detektCliArguments + "--create-baseline")
    argumentProviders.add(detektPluginArgumentProvider)
    inputs.files(detektExistingSourceDirs).withPropertyName("sources")
    inputs.file(detektConfigFile).withPropertyName("config")
    inputs.files(detektPluginClasspath).withPropertyName("plugins").withNormalizer(ClasspathNormalizer::class)
    outputs.file(detektBaselineFile).withPropertyName("baseline")
}

// --- Coverage: Kover (0.9.x). Measures the JVM target; excludes generated serializers. ---
kover {
    reports {
        filters {
            excludes {
                classes("*\$\$serializer")
            }
        }
        verify {
            // Actuals: run ./gradlew koverXmlReport, then update dev/measurements.toml
            // through dev/measurements_ledger.py (coverage_* keys). Do not restate numbers here.
            // These beta gates leave a small ratchet margin so incidental line
            // churn can land with tests, while material regressions fail `check`.
            rule("line coverage ratchet") {
                minBound(82, CoverageUnit.LINE, AggregationType.COVERED_PERCENTAGE)
            }
            rule("instruction coverage ratchet") {
                minBound(76, CoverageUnit.INSTRUCTION, AggregationType.COVERED_PERCENTAGE)
            }
            rule("branch coverage ratchet") {
                minBound(49, CoverageUnit.BRANCH, AggregationType.COVERED_PERCENTAGE)
            }
        }
    }
}

val detektBaselineBudgetCheck by tasks.registering(Exec::class) {
    description = "Fails if detekt-baseline.xml grows beyond detekt-baseline-budget.json."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    commandLine(layout.projectDirectory.file("tools/check-detekt-baseline-budget").asFile.absolutePath)
    inputs.file(layout.projectDirectory.file("detekt-baseline.xml"))
    inputs.file(layout.projectDirectory.file("detekt-baseline-budget.json"))
    outputs.upToDateWhen { false }
}

tasks.named("check").configure {
    // AR-34: exercise publish-path-only metadata compilation during normal check.
    dependsOn("metadataMainClasses")
    dependsOn("koverVerify")
    dependsOn(detektBaselineBudgetCheck)
    dependsOn(detekt)
}

// --- API docs: Dokka 2.x. HTML output (`./gradlew dokkaGenerate` → build/dokka/html). ---
// Note: Dokka's *Javadoc* format does not support KMP projects by design (it models Kotlin
// as consumed from Java, which is meaningless for Kotlin/Native and Kotlin/JS — Kotlin/dokka#1753).
// For the Maven Central javadoc-jar requirement we therefore package the HTML output, which
// Sonatype accepts (the jar contents are arbitrary). Wired but not added to any publication yet —
// the publication itself is finalized in the later publishing pass.
dokka {
    moduleName.set(providers.gradleProperty("POM_NAME"))
    dokkaSourceSets.configureEach {
        sourceLink {
            localDirectory.set(file("src"))
            remoteUrl("https://github.com/torad-labs/aisdk-kotlin/blob/v$version/src")
            remoteLineSuffix.set("#L")
        }
        externalDocumentationLinks.register("kotlinx-coroutines") {
            url("https://kotlinlang.org/api/kotlinx.coroutines/")
        }
        externalDocumentationLinks.register("kotlinx-serialization") {
            url("https://kotlinlang.org/api/kotlinx.serialization/")
        }
        externalDocumentationLinks.register("ktor") {
            url("https://api.ktor.io/")
        }
    }
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
        // Deliberate snapshot channel for main-branch nightlies. Release/staging
        // repositories below still reject SNAPSHOT versions unless this repository
        // is explicitly selected with SNAPSHOT_PUBLISH=1.
        maven {
            name = "CentralSnapshots"
            url = uri("https://central.sonatype.com/repository/maven-snapshots/")
            credentials {
                username = providers.environmentVariable("SONATYPE_USERNAME").orNull
                password = providers.environmentVariable("SONATYPE_PASSWORD").orNull
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
            issueManagement {
                system.set("GitHub")
                url.set("https://github.com/torad-labs/aisdk-kotlin/issues")
            }
            ciManagement {
                system.set("GitHub Actions")
                url.set("https://github.com/torad-labs/aisdk-kotlin/actions")
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
    isRequired = publishingToRemoteRepository.get() && signingKey.isPresent && signingPassword.isPresent
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

// checkKotlinAbi reads the dump files written by updateKotlinAbi; when both tasks are
// requested in one invocation Gradle 9 flags the shared output as an implicit dependency.
// mustRunAfter (not dependsOn) keeps checkKotlinAbi runnable on its own.
tasks.named("checkKotlinAbi").configure {
    mustRunAfter(tasks.named("updateKotlinAbi"))
}

tasks.withType<PublishToMavenRepository>().configureEach {
    doFirst {
        val repoName = repository.name
        val isSnapshotRepository = repoName == "CentralSnapshots"
        val isSnapshotVersion = version.toString().endsWith("-SNAPSHOT")
        if (isSnapshotRepository) {
            require(snapshotPublishEnabled.get()) {
                "Publication to CentralSnapshots requires SNAPSHOT_PUBLISH=1 so snapshots are deliberate."
            }
            require(isSnapshotVersion) {
                "Publication to CentralSnapshots requires a -SNAPSHOT version; got $version."
            }
            require(
                providers.environmentVariable("SONATYPE_USERNAME").isPresent &&
                    providers.environmentVariable("SONATYPE_PASSWORD").isPresent,
            ) {
                "Publication to CentralSnapshots requires SONATYPE_USERNAME and SONATYPE_PASSWORD credentials."
            }
            return@doFirst
        }
        require(!isSnapshotVersion) {
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
