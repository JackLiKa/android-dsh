plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Default payload is the pinned reference runtime. Local builds may point at an alternative
// payload.zip with `-PpayloadSha=<sha256>` (used only for local packaging/verification).
val payloadSha = providers.gradleProperty("payloadSha")
    .getOrElse("6f5003bd7729135683460d9d85cb0c1b2673dbfea6584046a0de8d6968345fb7")

android {
    namespace = "com.example.adsh"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.adsh"
        minSdk = 29
        // The bundled Android Node ELF needs executable app-private storage on the tested device.
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "PAYLOAD_SHA256", "\"$payloadSha\"")
        // 75 s for phones; raise locally (e.g. -PstartupTimeoutMs=300000) when running the
        // arm64 runtime through the x86_64 emulator's ARM translation, which boots much slower.
        buildConfigField(
            "long",
            "STARTUP_TIMEOUT_MS",
            "${providers.gradleProperty("startupTimeoutMs").getOrElse("75000")}L",
        )
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    lint {
        // 新版 androidx lint 检查器与 AGP 8.7 自带的 Kotlin 分析 API 不兼容（ICCE），
        // 属于 lint/依赖自身的崩溃而非工程代码问题；仅禁用会崩溃的检查，其余保持开启。
        disable += "NullSafeMutableLiveData"
        disable += "FlowOperatorInvokedInComposition"
        disable += "UnrememberedMutableState"
        disable += "RememberInComposition"
        disable += "AutoboxingStateCreation"
        // FrequentlyChangingValue 检查器在 AGP 8.7.3 下会经 RememberInCompositionDetectorKt 触发 ICCE。
        disable += "FrequentlyChangingValue"
        // ComposableStateFlowValueDetector 在 AGP 8.7.3 下会经 ComposableUtilsKt 触发 ICCE。
        disable += "StateFlowValueCalledInComposition"
        // targetSdk 28 是运行时 Node ELF 可在应用私有目录执行的前提，无法提高到 Play 要求。
        disable += "ExpiredTargetSdkVersion"
        abortOnError = true
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.yaml:snakeyaml:2.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Offline Chinese speech recognition (Apache-2.0). Model lives in assets/vosk/model-cn.
    implementation("com.alphacephei:vosk-android:0.3.47")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

val checkRuntimePayload by tasks.registering {
    doLast {
        check(layout.projectDirectory.file("src/main/assets/dsh/payload.zip").asFile.isFile) {
            "缺少 dsh 运行时 payload；请按 README 的「准备运行时」步骤生成后再构建 APK"
        }
    }
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(checkRuntimePayload) }
