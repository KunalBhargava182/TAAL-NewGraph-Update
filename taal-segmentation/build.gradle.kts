plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.purnacardio.signal.pcg.android"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        // Ships consumer-rules.pro so the host app's R8 config needs no segmenter-specific edits.
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    sourceSets["main"].java.srcDirs("src/main/kotlin")
}

dependencies {
    // `api`, not `implementation`: SegmentationResult and CardiacSegmenter are the return and
    // configuration types of this module's public API, so consumers must see them.
    api(project(":taal-segmentation-core"))

    // `api` so the host app resolves ONNX Runtime transitively and does not have to declare it.
    //
    // 1.18 is a FLOOR, not a preference. The model is exported at ONNX IR version 10, and
    // ONNX Runtime 1.17.x supports at most IR 9 — on 1.17.x `OrtSession` throws
    // ORT_INVALID_ARGUMENT for every recording, `TcnSegmenterRunner` construction fails, and a
    // caller following the recommended `runCatching { ... }.getOrNull()` pattern sees a null
    // segmenter and reports "no heart sounds" on every capture. It never loads once, and the
    // failure looks exactly like a capture-quality problem.
    //
    // Bumped 1.19.2 -> 1.29.0 (2026-09-04) to pick up the libonnxruntime4j_jni.so 16KB-page
    // alignment fix (upstream PR #24947, merged after 1.19.2 shipped) — 1.19.2's JNI .so wasn't
    // 16KB-aligned, which triggered Android's debug-build "app compatibility" warning dialog.
    //
    // If you must pin a different version, use >= 1.18 and check the model's IR version first:
    //   od -An -tu1 -N4 tcn_c200_cardiac_seg.onnx
    api("com.microsoft.onnxruntime:onnxruntime-android:1.29.0")

    // For the TaalCardiacSegmentation bridge (com.musediagnostics.taal.segmentation): segment()
    // dispatches onto Dispatchers.Default itself so callers never need their own dispatcher hop.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // On-device smoke test only — proves ONNX Runtime actually loads and runs inference on real
    // hardware, which a JVM-only unit test cannot: onnxruntime-android ships native .so libraries.
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
}
