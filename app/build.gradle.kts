// android {} 块内 `java` 会被 DSL 作用域遮蔽，Properties 需显式导入
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.xuyizhou.videocompressor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xuyizhou.videocompressor"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // GitHub Releases 自更新：仓库地址与版本号同处维护
        buildConfigField("String", "UPDATE_OWNER", "\"xu-yi-zhou\"")
        buildConfigField("String", "UPDATE_REPO", "\"VideoCompressor\"")

        javaCompileOptions {
            annotationProcessorOptions {
                arguments["room.schemaLocation"] = "$projectDir/schemas"
            }
        }
    }

    // AGP 8 默认关闭 BuildConfig，自更新需读取 BuildConfig.VERSION_NAME
    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        // 正式签名：keystore.properties（仓库内为占位模板，本机真实值靠 skip-worktree
        // 保护，仿 NetdiskConfig 先例）。文件与 keystore 均存在才启用，否则他人
        // clone 后仍可构建（产物为未签名包）。
        val propsFile = rootProject.file("keystore.properties")
        if (propsFile.exists()) {
            val props = Properties().apply {
                propsFile.inputStream().use { load(it) }
            }
            val store = rootProject.file(props.getProperty("storeFile"))
            if (store.exists()) {
                create("release") {
                    storeFile = store
                    storePassword = props.getProperty("storePassword")
                    keyAlias = props.getProperty("keyAlias")
                    keyPassword = props.getProperty("keyPassword")
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")

    // ViewModel + Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.51.1")
    ksp("com.google.dagger:hilt-compiler:2.51.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Navigation Compose
    implementation("androidx.navigation:navigation-compose:2.8.4")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Gson (JSON serialization for CompressService)
    implementation("com.google.code.gson:gson:2.10.1")

    // OkHttp（百度网盘 PCS 上传的官方 API 客户端）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
