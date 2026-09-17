plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nextnotif.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nextnotif.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Public SDK settings for the existing NextNotif Android registration.
        // Gradle properties can override these together for a private build.
        for ((resource, config) in mapOf(
            "google_app_id" to ("nextnotif.fcm.appId" to "1:223835571995:android:e18d5607a74d20ddfff5be"),
            "google_api_key" to ("nextnotif.fcm.apiKey" to "AIzaSyC15xJn01Yn8h6F7UcsYnJ54Qe4J0pStLY"),
            "gcm_defaultSenderId" to ("nextnotif.fcm.senderId" to "223835571995"),
            "project_id" to ("nextnotif.fcm.projectId" to "nextnotif-5bcf9"),
        )) {
            resValue("string", resource, providers.gradleProperty(config.first).orElse(config.second).get())
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            isDebuggable = true
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
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    // Pins fragment above 1.3.0 (a transitive dep pulls in 1.1.0, which trips
    // InvalidFragmentVersionForActivityResult in activity 1.9.1).
    implementation("androidx.fragment:fragment:1.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.activity:activity-compose:1.9.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.firebase:firebase-auth-ktx:23.1.0")
    implementation("com.google.firebase:firebase-database-ktx:21.0.0")
    implementation("com.google.firebase:firebase-messaging:24.1.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")

    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
}
