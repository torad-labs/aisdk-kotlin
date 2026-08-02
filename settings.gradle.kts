rootProject.name = "torad-aisdk"

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

// Security pins for the SETTINGS plugin classpath. Must sit AFTER pluginManagement —
// Gradle rejects a `buildscript` block before it ("cannot appear before pluginManagement").
//
// This is a different resolution from build.gradle.kts's `buildscript {}` block: the
// settings classpath is resolved before that file is ever evaluated, so a force there
// cannot reach it. That distinction was measurable, not theoretical — after pinning only
// the project buildscript, the submitted dependency graph carried BOTH versions of every
// affected module (bcprov ['1.79','1.84'], jackson-databind ['2.15.3','2.18.9'],
// commons-lang3 ['3.16.0','3.18.0']) and 12 of 15 Dependabot advisories stayed open,
// every one anchored to settings.gradle.kts. The three that closed — jose4j, jdom2,
// opentelemetry-api — were exactly the three that exist on no other classpath.
//
// Versions are READ FROM gradle/libs.versions.toml rather than repeated here. Two copies
// that must agree with nothing linking them is a drift generator; the catalog stays the
// single source of truth, and a missing key fails the build instead of silently
// degrading to an unpinned classpath.
buildscript {
    val catalog = file("gradle/libs.versions.toml").readText()
    fun pin(key: String): String =
        Regex("""^\s*${Regex.escape(key)}\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)
            .find(catalog)
            ?.groupValues
            ?.get(1)
            ?: error(
                "gradle/libs.versions.toml has no version '$key': the settings-classpath " +
                    "security pin cannot be applied. Fix the catalog rather than dropping the pin.",
            )

    configurations.classpath {
        resolutionStrategy {
            force(
                "org.bouncycastle:bcprov-jdk18on:${pin("bouncycastle")}",
                "org.bouncycastle:bcpkix-jdk18on:${pin("bouncycastle")}",
                "com.fasterxml.jackson.core:jackson-core:${pin("jackson")}",
                "com.fasterxml.jackson.core:jackson-databind:${pin("jackson")}",
                "org.bitbucket.b_c:jose4j:${pin("jose4j")}",
                "org.jdom:jdom2:${pin("jdom2")}",
                "org.apache.commons:commons-lang3:${pin("commons-lang3")}",
                "org.apache.httpcomponents:httpclient:${pin("httpclient")}",
            )
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

// Custom detekt ruleset: the project's architectural tenets as JVM lints, so they
// surface in the IDE (detekt plugin) + `./gradlew check` for ALL developers — not only
// Claude's edits via the ast-grep PreToolUse hook. Packaged as a plugin JAR on the
// root project's detektPlugins classpath.
include(":detekt-rules")
