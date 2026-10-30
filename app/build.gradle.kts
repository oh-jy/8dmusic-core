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
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
