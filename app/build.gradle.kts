plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val appVersionName: String = (findProperty("versionName") as String?) ?: "0.1.0"

/** "1.2.3" → 10203。OTAで上書きするため単調増加させる。 */
fun versionCodeOf(name: String): Int {
    val p = name.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 } + listOf(0, 0, 0)
    return p[0] * 10000 + p[1] * 100 + p[2]
}

android {
    namespace = "com.saitateppei.opencloudflac"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.saitateppei.opencloudflac"
        minSdk = 26
        targetSdk = 35
        versionCode = versionCodeOf(appVersionName).coerceAtLeast(1)
        versionName = appVersionName
        // 日本語と英語以外のライブラリ翻訳を入れない
        androidResources.localeFilters += listOf("ja", "en")
        buildConfigField("String", "UPDATE_REPO", "\"${findProperty("updateRepo") ?: ""}\"")
    }

    signingConfigs {
        create("release") {
            // GitHub Actions または手元で環境変数を設定したときだけ使う
            val ks = System.getenv("SIGNING_KEYSTORE_PATH")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = System.getenv("SIGNING_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                // PKCS12(keytoolの既定)では鍵のパスワード=ストアのパスワードなので、未設定ならそれを使う
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")?.takeIf { it.isNotEmpty() }
                    ?: System.getenv("SIGNING_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8で未使用コードとリソースを除去してAPKを小さくする
            isMinifyEnabled = true
            isShrinkResources = true
            if (System.getenv("SIGNING_KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // Google Play向けの依存関係メタデータはサイドロード配布では不要
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources.excludes += listOf(
            "META-INF/*.version", "META-INF/*.kotlin_module", "META-INF/LICENSE*", "META-INF/NOTICE*",
            "kotlin/**", "DebugProbesKt.bin",
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
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.04.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.9")

    val room = "2.7.1"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    implementation("androidx.work:work-runtime-ktx:2.10.1")

    val media3 = "1.6.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-session:$media3")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.10.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.test:core-ktx:1.7.0")
    testImplementation("androidx.work:work-testing:2.10.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

// Robolectricのテストはクラスごとに別JVMで動かす(静的な状態がテスト間で残らないように)。
// その分、CPU数に合わせて並列に走らせる。
tasks.withType<Test>().configureEach {
    forkEvery = 1
    maxParallelForks = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
}
