// The `torad-aisdk-rules` custom detekt ruleset — the project's architectural tenets
// (mirrors of the ast-grep PreToolUse hook rules) expressed as detekt rules so every
// developer sees them in-IDE and `./gradlew check` enforces them, not just Claude's edits.
//
// A plain JVM module (not KMP): detekt loads rules from a JAR on the detektPlugins
// classpath. Rules run WITHOUT type resolution (matching the root detekt config), so they
// operate on the PSI only — same single-file scope as the ast-grep rules they mirror.
plugins {
    // No version: the Kotlin Gradle Plugin is already on the shared build classpath via the
    // root project's Multiplatform plugin, so applying it `alias`ed (with a version) is
    // rejected as a version conflict. Apply it unversioned from the existing classpath.
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // `compileOnly`: the detekt-api is provided by the detekt runtime that loads the JAR.
    compileOnly(libs.detekt.api)

    // Each rule gets a unit test that feeds it a code snippet and asserts the findings.
    testImplementation(libs.detekt.test)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
