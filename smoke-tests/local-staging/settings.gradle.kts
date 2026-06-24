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
        val smokeRepository = providers.gradleProperty("smokeRepository").orNull
            ?: file("../../build/staging-deploy").absolutePath
        maven {
            name = "AiSdkSmokeRepository"
            url = uri(smokeRepository)
        }
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

rootProject.name = "aisdk-local-staging-smoke"
include(":jvm-consumer", ":kmp-consumer", ":android-consumer")
