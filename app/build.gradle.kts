plugins {
    id("com.android.application")
}

android {
    namespace = "com.yas.linedebugger"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.yas.linedebugger"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-debug"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.1")
}
