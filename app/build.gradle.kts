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
            commandLine("powershell", "-NoProfile", "-Command", "Get-Date -Format yyMMddHH")
        }.standardOutput.asText.get().trim().toInt()
        versionName = "0.1.0"
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
