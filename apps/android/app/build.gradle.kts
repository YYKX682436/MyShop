plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.compose") }
val authority = providers.gradleProperty("authorityOrigin").orElse("https://mc.mc520.top").get()
require(authority.matches(Regex("https://[A-Za-z0-9.-]+(?::443)?/?"))) { "authorityOrigin must be an HTTPS origin" }
android {
    namespace = "aikayun.muchen.shop"
    compileSdk = 37
    defaultConfig {
        applicationId = "aikayun.muchen.shop"
        minSdk = 31
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"
        buildConfigField("String", "AUTHORITY_ORIGIN", "\"${authority.trimEnd('/')}\"")
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }
    buildTypes {
        debug { applicationIdSuffix = ".phase1test" }
        release { isMinifyEnabled = true; isShrinkResources = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") }
    }
    buildFeatures { buildConfig = true; compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21 } }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation:1.12.1")
    implementation("androidx.webkit:webkit:1.15.0")
    implementation("io.github.neoruaa.glasmio:glasmio-ui:0.1.4")
    implementation("com.caverock:androidsvg-aar:1.4")
    androidTestCompileOnly(files("${androidComponents.sdkComponents.sdkDirectory.get().asFile}/platforms/android-36/optional/android.test.base.jar", "${androidComponents.sdkComponents.sdkDirectory.get().asFile}/platforms/android-36/optional/android.test.runner.jar"))
}

// The on-device fixture executes the actual website viewport entry, never a test-only reimplementation.
abstract class FixtureAssetsTask : DefaultTask() {
    @get:InputFile abstract val entry: RegularFileProperty
    @get:OutputDirectory abstract val output: DirectoryProperty
    @TaskAction fun generate() {
        val text = entry.get().asFile.readText().substringAfter("String.raw`").substringBeforeLast('`')
        output.get().asFile.apply { mkdirs() }.resolve("viewport.js").writeText(text)
    }
}
val prepareFixtureAssets = tasks.register<FixtureAssetsTask>("prepareFixtureAssets") {
    entry.set(rootProject.layout.projectDirectory.file("../web/app/viewport-compatibility-script.tsx"))
    output.set(layout.buildDirectory.dir("generated/fixture-assets"))
}
androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(prepareFixtureAssets, FixtureAssetsTask::output)
}
