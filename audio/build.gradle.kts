plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.walnutgeek.stsloop.audio"
    compileSdk = 37
    defaultConfig {
        minSdk = 31
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // sherpa-onnx reads each model asset whole into memory; storing the
    // ~70 MB of .onnx uncompressed spares an inflate on every load (measured
    // 1.27 s vs 1.56 s). This covers this module's test APK only; :app sets
    // the same for the app APK.
    androidResources { noCompress += "onnx" }
}

dependencies {
    api(project(":core"))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit4)
}

// Stt is the single chokepoint in front of sherpa-onnx stream creation (a
// wrong hotwords call is an uncatchable _Exit). Fail the build if anything
// else constructs an OnlineRecognizer. The vendored API is exempt.
val checkSingleChokepoint = tasks.register("checkSingleChokepoint") {
    val sources = files("src", rootProject.file("app/src")).asFileTree.matching { include("**/*.kt", "**/*.java") }
    inputs.files(sources)
    doLast {
        val construct = Regex("""\bOnlineRecognizer\s*\(""")
        val offenders = sources.files.filter { f ->
            val p = f.invariantSeparatorsPath
            !p.endsWith("/audio/speech/Stt.kt") && "/com/k2fsa/" !in p && construct.containsMatchIn(f.readText())
        }
        check(offenders.isEmpty()) { "OnlineRecognizer must only be constructed in Stt.kt; found in: $offenders" }
    }
}
tasks.named("check") { dependsOn(checkSingleChokepoint) }
