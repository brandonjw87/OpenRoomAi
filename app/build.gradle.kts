plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.openroomai.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.openroomai.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 167
        versionName = "1.67"
        // Phones are ARM; x86_64 keeps emulators/Chromebooks working. 32-bit x86 is emulator-only.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }
    packaging {
        // Compress native libs in the APK (smaller download). Safe on 16 KB-page devices because
        // every bundled .so (ARCore, LiteRT) has 16 KB-aligned LOAD segments — verified.
        jniLibs { useLegacyPackaging = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    // Memory-map the detector model straight out of the APK.
    androidResources { noCompress += "tflite" }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("com.google.ar:core:1.46.0")
    // On-device furniture recognition (EfficientDet-Lite0, COCO). 16 KB page aligned.
    implementation("com.google.ai.edge.litert:litert:1.4.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Robolectric's own downloader bypasses Gradle (and proxies); let Gradle fetch the Android runtime instead.
val robolectricRuntime by configurations.creating
dependencies { robolectricRuntime("org.robolectric:android-all-instrumented:15-robolectric-12650502-i7") }
val robolectricDir = layout.buildDirectory.dir("robolectric-jars")
val fetchRobolectric by tasks.registering(Copy::class) {
    from(robolectricRuntime)
    into(robolectricDir)
}
tasks.withType<Test>().configureEach {
    dependsOn(fetchRobolectric)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", robolectricDir.get().asFile.absolutePath)
    maxHeapSize = "3g"
}
