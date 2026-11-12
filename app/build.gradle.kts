plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties

val buildHour = SimpleDateFormat("yyMMddHH").format(Date())
val versionCounterFile = rootDir.resolve("docs/signing/version-code.properties")
val versionCounter = Properties().apply {
    versionCounterFile.takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
val sameHour = versionCounter.getProperty("hour") == buildHour
// The last sequence value is reused if more than nine releases are built in one hour.
val buildNumber = if (sameHour) (versionCounter.getProperty("count", "0").toInt() + 1).coerceAtMost(9) else 1
// 仅正式发布累计次数；同小时测试/debug 构建复用当前序号，不消耗打包次数。
val releaseBuild = gradle.startParameter.taskNames.any { it.contains("assembleRelease") }
if (releaseBuild) {
    versionCounterFile.parentFile.mkdirs()
    versionCounter.setProperty("hour", buildHour)
    versionCounter.setProperty("count", buildNumber.toString())
    versionCounterFile.outputStream().use { versionCounter.store(it, null) }
}
// Debug builds do not publish a version code and must not advance the release counter.
val versionSequence = if (releaseBuild) buildNumber else
    if (sameHour) versionCounter.getProperty("count", "1").toInt().coerceIn(1, 9) else 1
val hourlyVersionCode = (buildHour + versionSequence).toInt()
val withAsr = providers.gradleProperty("withAsr").map { it.toBoolean() }.getOrElse(false)

android {
    namespace = "j5i.spatial8d"
    compileSdk = 34
    buildFeatures { buildConfig = true; aidl = true }

    defaultConfig {
        applicationId = "j5i.spatial8d.native"
        minSdk = 24
        targetSdk = 34
        // yyMMddHH + 当小时发布序号，例如 261104101；九位数始终处于 Int 范围。
        versionCode = hourlyVersionCode
        versionName = "2.17.5"
        buildConfigField("boolean", "WITH_ASR", withAsr.toString())
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    if (!withAsr) sourceSets.getByName("main").assets.setSrcDirs(emptyList<String>())

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
    implementation(project(":provider-sdk"))
    if (withAsr) implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.8")
    else compileOnly("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.8")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup:gifencoder:0.10.1")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.2.0")
    testImplementation("org.json:json:20240303")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }

android {
    signingConfigs {
        create("release") {
            storeFile = file("${rootDir}/docs/signing/8dmusic-native-release.jks")
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
