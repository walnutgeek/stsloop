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
    // 1.27 s vs 1.56 s). This applies to this module's test APK only: the app
    // APK takes its own androidResources setting.
    androidResources { noCompress += "onnx" }
}

dependencies {
    api(project(":core"))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit4)
}
