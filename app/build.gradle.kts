plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.variant44gaze"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.variant44gaze"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    val mediapipeDebugVersion = "0.10.21"
    val mediapipeReleaseVersion = "0.10.26"
    debugImplementation("com.google.mediapipe:tasks-core:$mediapipeDebugVersion")
    debugImplementation("com.google.mediapipe:tasks-vision:$mediapipeDebugVersion")
    releaseImplementation("com.google.mediapipe:tasks-core:$mediapipeReleaseVersion")
    releaseImplementation("com.google.mediapipe:tasks-vision:$mediapipeReleaseVersion")
}
