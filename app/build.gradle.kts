plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "jiangyi.spatial8d"
    compileSdk = 34

    defaultConfig {
        applicationId = "jiangyi.spatial8d.native"
        minSdk = 24
        targetSdk = 34
        versionCode = providers.exec {
            commandLine("powershell", "-NoProfile", "-Command", "Get-Date -Format yyMMddHHmm")
        // 保留发布时间十位格式；Android Int 上限内按位保留低 31 位，仍随时间递增。
        }.standardOutput.asText.get().trim().toLong().and(Int.MAX_VALUE.toLong()).toInt()
        versionName = "1.1.7"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// 发布签名只从本机环境读取，密钥与密码均不进入版本库。
val releaseStorePassword = providers.environmentVariable("SPATIAL8D_KEYSTORE_PASSWORD")

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    testImplementation(kotlin("test"))
}

android {
    signingConfigs {
        create("release") {
            storeFile = file("${rootDir}/build/signing/8dmusic-native-release.jks")
            storePassword = releaseStorePassword.orNull
            keyAlias = "spatial8d"
            keyPassword = releaseStorePassword.orNull
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (releaseStorePassword.isPresent) signingConfigs.getByName("release") else null
        }
    }
}
