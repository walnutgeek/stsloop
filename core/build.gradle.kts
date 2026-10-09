import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM, per ADR-0002. android.jar is never on this module's
// classpath, so any `android.*` import fails compilation. Do not add an
// Android plugin or an Android dependency here.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget = JvmTarget.JVM_17 }
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
}
