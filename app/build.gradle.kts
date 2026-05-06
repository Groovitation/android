import com.android.build.api.variant.BuildConfigField
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.0"
    id("com.google.gms.google-services")
}

val groovitationProdBaseUrl = "https://groovitation.blaha.io"
val elPasoProdBaseUrl = "https://chucopedia.blaha.io"
val defaultLocalBaseUrl = "http://10.0.2.2:3000"
val configuredLocalBaseUrl = providers.gradleProperty("groovitationLocalBaseUrl")
    .orElse(providers.environmentVariable("GROOVITATION_LOCAL_BASE_URL"))
    .orElse(defaultLocalBaseUrl)
    .get()
    .trim()
    .removeSuffix("/")

val brandVersions = Properties().apply {
    file("brand-versions.properties").inputStream().use { load(it) }
}

fun brandVersionCode(brand: String): Int =
    brandVersions.getProperty("$brand.versionCode")?.toIntOrNull()
        ?: error("Missing integer $brand.versionCode in brand-versions.properties")

fun brandVersionName(brand: String): String =
    brandVersions.getProperty("$brand.versionName")
        ?.takeIf { it.isNotBlank() }
        ?: error("Missing $brand.versionName in brand-versions.properties")

fun String.asBuildConfigString(): String = "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "io.blaha.groovitation"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.blaha.groovitation"
        minSdk = 28
        targetSdk = 35
        versionCode = brandVersionCode("groovitation")
        versionName = brandVersionName("groovitation")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
    }

    // Brand flavors produce separate installable apps; server flavors choose production vs fixture/local backends.
    flavorDimensions += listOf("brand", "server")
    productFlavors {
        create("groovitation") {
            dimension = "brand"
            versionCode = brandVersionCode("groovitation")
            versionName = brandVersionName("groovitation")
            manifestPlaceholders["appLinkHost"] = "groovitation.blaha.io"
            manifestPlaceholders["oauthCallbackScheme"] = "groovitation"
        }
        create("elPaso") {
            dimension = "brand"
            applicationIdSuffix = ".chucopedia"
            versionCode = brandVersionCode("elPaso")
            versionName = brandVersionName("elPaso")
            manifestPlaceholders["appLinkHost"] = "chucopedia.blaha.io"
            manifestPlaceholders["oauthCallbackScheme"] = "groovitation"
        }
        create("prod") {
            dimension = "server"
        }
        create("local") {
            dimension = "server"
            // Local server for CI testing (10.0.2.2 = host localhost from emulator).
            // CI can override this for fixture-backed lanes, including dynamic ports on the shared runner.
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

androidComponents {
    onVariants { variant ->
        val flavorMap = variant.productFlavors.toMap()
        val brand = flavorMap["brand"] ?: error("Missing brand flavor for ${variant.name}")
        val server = flavorMap["server"] ?: error("Missing server flavor for ${variant.name}")

        val brandDisplayName = when (brand) {
            "groovitation" -> "Groovitation"
            "elPaso" -> "Chucopedia"
            else -> error("Unknown brand flavor: $brand")
        }
        val appLinkHost = when (brand) {
            "groovitation" -> "groovitation.blaha.io"
            "elPaso" -> "chucopedia.blaha.io"
            else -> error("Unknown brand flavor: $brand")
        }
        val publicArtifactSlug = when (brand) {
            "groovitation" -> "groovitation"
            "elPaso" -> "chucopedia"
            else -> error("Unknown brand flavor: $brand")
        }
        val brandProdBaseUrl = when (brand) {
            "groovitation" -> groovitationProdBaseUrl
            "elPaso" -> elPasoProdBaseUrl
            else -> error("Unknown brand flavor: $brand")
        }
        val baseUrl = when (server) {
            "prod" -> brandProdBaseUrl
            "local" -> configuredLocalBaseUrl
            else -> error("Unknown server flavor: $server")
        }
        val versionName = brandVersionName(brand)
        val versionUrl = "$baseUrl/android/${publicArtifactSlug}-version.json"

        variant.buildConfigFields.put("BASE_URL", BuildConfigField("String", baseUrl.asBuildConfigString(), "Hotwire start URL base"))
        variant.buildConfigFields.put("BRAND_ID", BuildConfigField("String", brand.asBuildConfigString(), "Internal Android brand flavor"))
        variant.buildConfigFields.put("SERVER_ID", BuildConfigField("String", server.asBuildConfigString(), "Android server flavor"))
        variant.buildConfigFields.put("APP_DISPLAY_NAME", BuildConfigField("String", brandDisplayName.asBuildConfigString(), "User-facing app name"))
        variant.buildConfigFields.put("APP_LINK_HOST", BuildConfigField("String", appLinkHost.asBuildConfigString(), "Verified app-link host"))
        variant.buildConfigFields.put("VERSION_CHECK_URL", BuildConfigField("String", versionUrl.asBuildConfigString(), "Per-brand update metadata URL"))
        variant.buildConfigFields.put(
            "USER_AGENT_EXTENSION",
            BuildConfigField("String", "${brandDisplayName} Android/${versionName} Hotwire Native Android/1.2.0".asBuildConfigString(), "Native WebView user-agent suffix")
        )
    }
}

fun copySingleApk(variantOutputDir: String, legacyOutputPath: String) {
    val sourceDir = layout.buildDirectory.dir(variantOutputDir).get().asFile
    val source = sourceDir.listFiles()
        ?.filter { it.isFile && it.extension == "apk" }
        ?.singleOrNull()
        ?: error("Expected exactly one APK in ${sourceDir.absolutePath}")
    val target = layout.buildDirectory.file(legacyOutputPath).get().asFile
    target.parentFile.mkdirs()
    source.copyTo(target, overwrite = true)
}

fun registerOrExtendCompatibilityTask(alias: String, target: String) {
    val existing = tasks.findByName(alias)
    if (existing == null) {
        tasks.register(alias) {
            group = "compatibility"
            dependsOn(target)
        }
    } else {
        existing.dependsOn(target)
    }
}

afterEvaluate {
    val legacyProdApk = tasks.register("copyGroovitationProdDebugLegacyApk") {
        dependsOn("assembleGroovitationProdDebug")
        doLast {
            copySingleApk("outputs/apk/groovitationProd/debug", "outputs/apk/prod/debug/app-prod-debug.apk")
        }
    }
    val legacyLocalApk = tasks.register("copyGroovitationLocalDebugLegacyApk") {
        dependsOn("assembleGroovitationLocalDebug")
        doLast {
            copySingleApk("outputs/apk/groovitationLocal/debug", "outputs/apk/local/debug/app-local-debug.apk")
        }
    }
    val legacyProdTestApk = tasks.register("copyGroovitationProdDebugAndroidTestLegacyApk") {
        dependsOn("assembleGroovitationProdDebugAndroidTest")
        doLast {
            copySingleApk("outputs/apk/androidTest/groovitationProd/debug", "outputs/apk/androidTest/prod/debug/app-prod-debug-androidTest.apk")
        }
    }

    registerOrExtendCompatibilityTask("assembleProdDebug", legacyProdApk.name)
    registerOrExtendCompatibilityTask("assembleLocalDebug", legacyLocalApk.name)
    registerOrExtendCompatibilityTask("assembleProdDebugAndroidTest", legacyProdTestApk.name)
    registerOrExtendCompatibilityTask("testProdDebugUnitTest", "testGroovitationProdDebugUnitTest")
    registerOrExtendCompatibilityTask("testLocalDebugUnitTest", "testGroovitationLocalDebugUnitTest")
    registerOrExtendCompatibilityTask("lintProdDebug", "lintGroovitationProdDebug")
    registerOrExtendCompatibilityTask("connectedLocalDebugAndroidTest", "connectedGroovitationLocalDebugAndroidTest")
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
    // #770: MockWebServer lets LocationWorkerInstrumentedTest drive the real
    // HTTP path so the worker's silent-skip branches (resolveLocationAuth
    // null-return specifically) stay observable in CI. Pinned to the same
    // okhttp version we use in production to keep class-loading consistent.
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // #770: TestListenableWorkerBuilder drives the worker in-process so the
    // app's Application.onCreate periodic LocationWorker can't race our
    // test's one-shot against the MockWebServer (pipeline #10836 failure).
    androidTestImplementation("androidx.work:work-testing:2.9.0")
    androidTestUtil("androidx.test:orchestrator:1.5.0")
}
