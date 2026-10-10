plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.walnutgeek.stsloop.app"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.walnutgeek.stsloop"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    androidResources { noCompress += "onnx" } // sherpa-onnx models: ~0.3 s faster load
}

dependencies {
    implementation(project(":audio"))
}
