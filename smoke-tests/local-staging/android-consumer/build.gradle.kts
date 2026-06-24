plugins {
    id("com.android.library")
}

val aisdkVersion = providers.gradleProperty("aisdkVersion").orElse("0.3.0-beta01")

android {
    namespace = "ai.torad.aisdk.smoke.android"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }
}

dependencies {
    implementation("ai.torad:torad-aisdk:${aisdkVersion.get()}")
}
