plugins { id("com.android.application") }
val authority = providers.gradleProperty("authorityOrigin").orElse("https://mc.mc520.top").get()
require(authority.matches(Regex("https://[A-Za-z0-9.-]+(?::443)?/?"))) { "authorityOrigin must be an HTTPS origin" }
android {
    namespace = "aikayun.muchen.shop"
    compileSdk = 36
    defaultConfig {
        applicationId = "aikayun.muchen.shop"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.1"
        buildConfigField("String", "AUTHORITY_ORIGIN", "\"${authority.trimEnd('/')}\"")
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }
    buildTypes {
        debug { applicationIdSuffix = ".phase1test" }
        release { isMinifyEnabled = false }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    androidTestCompileOnly(files("${androidComponents.sdkComponents.sdkDirectory.get().asFile}/platforms/android-36/optional/android.test.base.jar", "${androidComponents.sdkComponents.sdkDirectory.get().asFile}/platforms/android-36/optional/android.test.runner.jar"))
}
