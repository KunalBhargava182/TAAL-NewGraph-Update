// taal-stemz-ui-kit — stemz-only pre-built screens (PcgScale recorder, review, save, saved list
// with wav+pdf share, heart-sound analysis report) on top of taal-stemz-core.
// Ships to the stemz team as taal-stemz-ui-kit.aar alongside taal-stemz-core.aar.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.musediagnostics.taal.stemz.uikit"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        aarMetadata { minCompileSdk = 34 }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            // Obfuscated: public API (entry activities, graph views, TaalStemzUiKit) stays
            // readable; screens and internals are renamed/shrunk.
            isMinifyEnabled = true
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
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { viewBinding = true }

    // Every resource in this library is named tsuk_* so it can't collide with (or be silently
    // overridden by) a same-named resource in the integrating app. Lint warns on violations.
    resourcePrefix = "tsuk_"
}

dependencies {
    api(project(":taal-stemz-core"))

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.0.0")
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.6")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // PcgScaleWaveformView draws the trace with MPAndroidChart (JitPack) — `api` because the
    // public PcgScaleWaveformView exposes its LineChart.
    api("com.github.PhilJay:MPAndroidChart:v3.1.0")

    testImplementation("junit:junit:4.13.2")
}
