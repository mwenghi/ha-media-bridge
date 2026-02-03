plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.hamediabridge"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.hamediabridge"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        manifestPlaceholders["appAuthRedirectScheme"] = "hamediabridge"
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.media)
    implementation(libs.appauth)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.work.runtime)
}
