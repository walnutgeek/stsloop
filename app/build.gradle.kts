plugins {
    alias(libs.plugins.android.application)
}

// Release signing comes from the environment; the release workflow sets these
// from repo secrets. Without them the release APK is built unsigned, so local
// builds and CI's `./gradlew build` need no key.
fun env(name: String): String? = providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }
val releaseKeystore = env("STSLOOP_KEYSTORE")

android {
    namespace = "com.walnutgeek.stsloop.app"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.walnutgeek.stsloop"
        minSdk = 31
        targetSdk = 37
        // A release tag sets both (scripts/release_version.py); other builds keep the defaults.
        versionCode = providers.gradleProperty("stsloop.versionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("stsloop.versionName").orNull ?: "0.1.0"
    }
    if (releaseKeystore != null) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = env("STSLOOP_KEYSTORE_PASSWORD")
                keyAlias = env("STSLOOP_KEY_ALIAS")
                keyPassword = env("STSLOOP_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            // No R8: sherpa-onnx's JNI finds the vendored Kotlin config classes and
            // their fields by name, which minification would rename. Native
            // libraries dominate the APK's size anyway.
            isMinifyEnabled = false
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    androidResources { noCompress += "onnx" } // bundled models (debug builds only): ~0.3 s faster load
}

dependencies {
    implementation(project(":audio"))
    // Scans the hub's pairing QR code through Play services: no camera permission, no camera code.
    implementation(libs.code.scanner)
}
