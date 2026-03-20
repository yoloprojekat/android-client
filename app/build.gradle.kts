plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.yolo.vozilo"
    compileSdk = 36


    defaultConfig {
        applicationId = "com.yolo.vozilo"
        minSdk = 24
        targetSdk = 36

        // --- ADDED: AUTOMATIC VERSION BUMP LOGIC ---
        // Looks for the -PversionCode parameter from GitHub Actions. 
        // If you build locally (where that parameter doesn't exist), it safely defaults to 10.
        val ciVersionCode = project.findProperty("versionCode")?.toString()?.toIntOrNull() ?: 10
        versionCode = ciVersionCode

        versionName = "3.2.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // --- SIGNING CONFIG ---
    signingConfigs {
        create("release") {
            // Checks for local file in project root, otherwise looks for GitHub Runner file
            val localKey = rootProject.file("keystore/android-key")
            val githubKey = rootProject.file("release.keystore")

            storeFile = if (localKey.exists()) localKey else githubKey

            // Injected via environment variables in GitHub Actions or your local gradle.properties
            storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("RELEASE_KEY_ALIAS")
            keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // --- ATTACH THE SIGNING CONFIG ---
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures { compose = true }

    // --- 16KB ALIGNMENT FIX ---
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
    compileSdkMinor = 1
    buildToolsVersion = "36.1.0"
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // Networking & Images
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.text.recognition)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

}