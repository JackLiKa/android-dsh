plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

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
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    lint {
        // 新版 androidx lint 检查器与 AGP 8.7 自带的 Kotlin 分析 API 不兼容（ICCE），
        // 属于 lint/依赖自身的崩溃而非工程代码问题；仅禁用会崩溃的检查，其余保持开启。
        disable += "NullSafeMutableLiveData"
        disable += "FlowOperatorInvokedInComposition"
        disable += "UnrememberedMutableState"
        disable += "RememberInComposition"
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
    testImplementation("junit:junit:4.13.2")
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
