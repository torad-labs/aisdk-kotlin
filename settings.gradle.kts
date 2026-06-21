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
