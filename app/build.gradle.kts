plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.webappwapper"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.webappwapper"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 🚨 1. ADDED: Enables Android Studio to generate the BuildConfig variables
    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        // 🚨 2. ADDED: Your Testing Environment (used when hitting the "Play" button)
        debug {
            buildConfigField("String", "WEBAPP_URL", "\"https://attendance-dtu--test-vybq7g6v.web.app/\"")
        }

        // 🚨 3. UPDATED: Your Live Environment (used when generating the final APK/Bundle)
        release {
            buildConfigField("String", "WEBAPP_URL", "\"https://attendance-dtu.web.app/\"")

            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    implementation(libs.play.services.auth)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    // Modern Credential Manager
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)

}