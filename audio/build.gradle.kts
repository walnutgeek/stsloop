plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.walnutgeek.stsloop.audio"
    compileSdk = 37
    defaultConfig {
        minSdk = 31
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core"))
}
