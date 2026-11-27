import java.io.File
import java.net.URI
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val authorityOrigin = providers.gradleProperty("APP_AUTHORITY_ORIGIN")
    .orElse("https://mc.mc520.top")
    .get()
    .trimEnd('/')
val parsedAuthority = runCatching { URI(authorityOrigin) }.getOrNull()
if (parsedAuthority?.scheme != "https" || parsedAuthority.rawAuthority.isNullOrBlank()
    || parsedAuthority.rawUserInfo != null || parsedAuthority.rawQuery != null || parsedAuthority.rawFragment != null
    || (!parsedAuthority.rawPath.isNullOrEmpty() && parsedAuthority.rawPath != "/")) {
    throw GradleException("APP_AUTHORITY_ORIGIN must be a plain HTTPS origin")
}
val escapedAuthority = authorityOrigin.replace("\\", "\\\\").replace("\"", "\\\"")
val signingFile = File(System.getProperty("user.home"), "Downloads/Android App正式签名密钥/签名密钥密码.env")
val signingLines = if (signingFile.isFile) signingFile.readLines() else emptyList()
val keystoreFile = File(System.getProperty("user.home"), "Downloads/Android App正式签名密钥/MUCHEN.jks")
val signingReady = signingLines.size >= 3 && keystoreFile.isFile

android {
    namespace = "aikayun.muchen.shop"
    compileSdk = 37

    defaultConfig {
        applicationId = "aikayun.muchen.shop"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.1"
        buildConfigField("String", "APP_AUTHORITY_ORIGIN", "\"$escapedAuthority\"")
    }

    signingConfigs {
        if (signingReady) {
            create("release") {
                storeFile = keystoreFile
                storePassword = signingLines[0]
                keyAlias = signingLines[1]
                keyPassword = signingLines[2]
                storeType = "JKS"
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            if (signingReady) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.configureEach {
    if (name == "assembleRelease" || name == "bundleRelease") {
        doFirst {
            if (!signingReady) throw GradleException("Formal release signing config is missing; no unsigned APK will be delivered")
        }
    }
}

dependencies {
    implementation(project(":liquid-glass"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.kyant.shapes)
}

tasks.withType<KotlinCompile>().configureEach {
    source(fileTree("src/commonMain/kotlin") { include("**/*.kt") })
}
