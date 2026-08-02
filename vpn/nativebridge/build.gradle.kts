plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.connectx.vpn.nativebridge"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir(rootProject.file("engine/go/build/android/jniLibs"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}
