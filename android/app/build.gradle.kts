plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

val privateSigningValues = listOf(
    "NEXTNOTIF_SIGNING_STORE_FILE",
    "NEXTNOTIF_SIGNING_STORE_PASSWORD",
    "NEXTNOTIF_SIGNING_KEY_ALIAS",
    "NEXTNOTIF_SIGNING_KEY_PASSWORD",
).associateWith { providers.environmentVariable(it).orNull }
val privateSigningConfigured = privateSigningValues.values.all { !it.isNullOrBlank() }
if (privateSigningValues.values.any { !it.isNullOrBlank() } && !privateSigningConfigured) {
    error("Private release signing requires all four NEXTNOTIF_SIGNING_* environment variables")
}
val compatibilityTestMinSdk = providers.gradleProperty("nextnotifCompatibilityTestMinSdk").orNull
if (compatibilityTestMinSdk != null && compatibilityTestMinSdk != "25") {
    error("The compatibility test override supports only API 25")
}

android {
    namespace = "com.nextnotif.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nextnotif.app"
        // API 25 is an explicit HTC private-MVP compatibility exception;
        // the ordinary private release and future public release stay at API 26.
        minSdk = if (compatibilityTestMinSdk == "25") 25 else 26
        targetSdk = 34
        versionCode = 5
        versionName = "1.0-private-mvp-dns-guard"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (privateSigningConfigured) {
            create("privateRelease") {
                storeFile = file(privateSigningValues.getValue("NEXTNOTIF_SIGNING_STORE_FILE")!!)
                storePassword = privateSigningValues.getValue("NEXTNOTIF_SIGNING_STORE_PASSWORD")
                keyAlias = privateSigningValues.getValue("NEXTNOTIF_SIGNING_KEY_ALIAS")
                keyPassword = privateSigningValues.getValue("NEXTNOTIF_SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (privateSigningConfigured) signingConfig = signingConfigs.getByName("privateRelease")
            // Keep the WebRTC audio bridge unminified. Older Samsung Android
            // builds abort inside the native audio thread when R8 rewrites
            // the JavaAudioDeviceModule integration.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("htcReceiver") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            resValue("string", "app_name", "NextNotif Receiver")
        }
        debug {
            isDebuggable = true
        }
        create("compat") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".compat"
            versionNameSuffix = "-compat"
            matchingFallbacks += listOf("debug")
            resValue("string", "app_name", "NextNotif Compat")
        }
        create("staging") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".staging"
            versionNameSuffix = "-staging"
            matchingFallbacks += listOf("debug")
            resValue("string", "app_name", "NextNotif Staging")
        }
        create("stagingFcm") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".staging.fcm"
            versionNameSuffix = "-staging-fcm"
            matchingFallbacks += listOf("debug")
            resValue("string", "app_name", "NextNotif FCM Test")
        }
        create("stagingSms") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".staging.sms"
            versionNameSuffix = "-staging-sms"
            matchingFallbacks += listOf("debug")
            resValue("string", "app_name", "NextNotif SMS Test")
        }
        create("stagingInbound") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".staging.inbound"
            versionNameSuffix = "-staging-inbound"
            matchingFallbacks += listOf("debug")
            resValue("string", "app_name", "NextNotif Inbound Test")
        }
        create("stagingCall") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".staging.call"
            versionNameSuffix = "-staging-call"
            matchingFallbacks += listOf("debug")
            resValue("string", "app_name", "NextNotif Call Test")
        }
        create("stagingCallReceiver") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".staging.call.receiver"
            versionNameSuffix = "-staging-call-receiver"
            matchingFallbacks += listOf("debug")
            resValue("string", "app_name", "NextNotif Call Receiver")
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
        buildConfig = true
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
    // 2.9.x remains compatible with this project's Kotlin 1.9 / compileSdk 34
    // toolchain while providing unique coroutine work for FCM queue cleanup.
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.activity:activity-compose:1.9.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Pinned native WebRTC core; no Stream account/service dependency. Live
    // media migration is separate from FCM wake/HTTPS message delivery.
    implementation("io.getstream:stream-video-webrtc-android:145.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Last pre-Kotlin-2 Firebase Messaging line; upgrading further requires a
    // coordinated Kotlin/Compose/AGP migration rather than a transport change.
    implementation("com.google.firebase:firebase-messaging:24.1.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")

    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
}
