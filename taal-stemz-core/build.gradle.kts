// taal-stemz-core — stemz-only audio engine SDK (no UI). Standalone: does NOT depend on
// taal-core; it carries its own copy of the engine plus the PcgScale graph maths, the Clean
// Graph display filter, BPM, WAV decoding and heart-sound segmentation (algorithm + ONNX model).
// Ships to the stemz team as taal-stemz-core.aar alongside taal-stemz-ui-kit.aar.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.musediagnostics.taal.stemz"
    compileSdk = 34

    defaultConfig {
        // 26, not 24 like taal-core: ONNX Runtime (heart-sound segmentation) needs API 26+.
        minSdk = 26
        aarMetadata { minCompileSdk = 34 }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "SDK_VERSION", "\"1.0.0\"")
    }

    buildTypes {
        release {
            // Obfuscated: the public API (see proguard-rules.pro) stays readable, everything
            // else is renamed/shrunk. Debug builds stay unobfuscated for development.
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        // 17 (not 1.8 like taal-core) because the segmentation sources were written for 17.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    sourceSets["main"].java.srcDirs("src/main/java", "src/main/kotlin")
    sourceSets["test"].java.srcDirs("src/test/java", "src/test/kotlin")

    // Keep the .onnx model uncompressed so it can be memory-mapped/read directly.
    androidResources { noCompress += "onnx" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Heart-sound segmentation runtime. `api` so the host resolves it transitively when this
    // module is consumed as a Gradle project; an integrator using the .aar file must declare it
    // themselves (AAR files carry no dependency metadata) — the integration guide says so.
    // 1.29.0: >= 1.18 is required for the model's ONNX IR v10, and 1.29.0 carries the
    // 16KB-page alignment fix for Android 15/16 devices.
    api("com.microsoft.onnxruntime:onnxruntime-android:1.29.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test-junit"))
}
