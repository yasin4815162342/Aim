plugins {
    id("com.android.application")
}

android {
    namespace = "com.yas.linedebugger"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.yas.linedebugger"
        minSdk = 26   // required for TYPE_APPLICATION_OVERLAY + Notification.Builder(ctx, channelId)
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

// AGP 9's built-in Kotlin compiler options (replaces the old android.kotlinOptions {} block).
// jvmTarget defaults to compileOptions.targetCompatibility anyway; set explicitly for clarity.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.1")
}
