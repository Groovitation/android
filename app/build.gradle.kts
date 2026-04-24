plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.0"
    id("com.google.gms.google-services")
}

val prodBaseUrl = "https://groovitation.blaha.io"
val defaultLocalBaseUrl = "http://10.0.2.2:3000"
val configuredLocalBaseUrl = providers.gradleProperty("groovitationLocalBaseUrl")
    .orElse(providers.environmentVariable("GROOVITATION_LOCAL_BASE_URL"))
    .orElse(defaultLocalBaseUrl)
    .get()
    .trim()
    .removeSuffix("/")

fun String.asBuildConfigString(): String = "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "io.blaha.groovitation"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.blaha.groovitation"
        minSdk = 28
        targetSdk = 35
        versionCode = 139
        versionName = "1.0.138"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["clearPackageData"] = "true"

        // Hotwire Native configuration (BASE_URL set by product flavor)
        buildConfigField("String", "USER_AGENT_EXTENSION", "\"Groovitation Android/${versionName} Hotwire Native Android/1.2.0\"")
    }

    // Product flavors for different server targets
    flavorDimensions += "server"
    productFlavors {
        create("prod") {
            dimension = "server"
            // Production server (Cloudflare)
            buildConfigField("String", "BASE_URL", prodBaseUrl.asBuildConfigString())
        }
        create("local") {
            dimension = "server"
            // Local server for CI testing (10.0.2.2 = host localhost from emulator).
            // CI can override this for fixture-backed lanes, including dynamic ports on the shared runner.
            buildConfigField("String", "BASE_URL", configuredLocalBaseUrl.asBuildConfigString())
            // No applicationIdSuffix - reuse same google-services.json
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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
        buildConfig = true
    }

    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    // Hotwire Native
    implementation("dev.hotwire:core:1.2.0")
    implementation("dev.hotwire:navigation-fragments:1.2.0")

    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")

    // Material Design
    implementation("com.google.android.material:material:1.11.0")

    // Custom Tabs (for OAuth browser flow)
    implementation("androidx.browser:browser:1.7.0")

    // Navigation
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.6")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.6")

    // Firebase (Push Notifications)
    implementation(platform("com.google.firebase:firebase-bom:32.7.0"))
    implementation("com.google.firebase:firebase-messaging-ktx")
    implementation("com.google.firebase:firebase-analytics-ktx")

    // Google Play Services (Location)
    implementation("com.google.android.gms:play-services-location:21.0.1")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.2.0")

    // WorkManager (background periodic tasks)
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Biometric Authentication
    implementation("androidx.biometric:biometric:1.1.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // HTTP Client (for token registration)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON parsing
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.5.1")
    androidTestImplementation("androidx.test.espresso:espresso-web:3.5.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestUtil("androidx.test:orchestrator:1.5.0")
}
