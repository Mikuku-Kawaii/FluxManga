import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

apply(from = rootProject.file("gradle/license-assets.gradle.kts"))

val updateChannel = Properties().apply {
    rootProject.file("release/update.properties").inputStream().use { load(it) }
}
val releaseStore = providers.environmentVariable("FLUX_SIGNING_STORE").orNull

android {
    namespace = "com.flux.manga"
    compileSdk = 36
    buildToolsVersion = "35.0.0"
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.flux.manga"
        minSdk = 31
        targetSdk = 36
        versionCode = providers.gradleProperty("fluxVersionCode").getOrElse("8").toInt()
        versionName = providers.gradleProperty("fluxVersionName").getOrElse("0.1.5")
        // Public release configuration only. Never put signing private keys in BuildConfig.
        fun updateString(name: String, property: String, fallback: String = "") {
            val value = providers.gradleProperty(property).getOrElse(fallback)
            require(value.none { it == '\n' || it == '\r' })
            buildConfigField("String", name, "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
        }
        updateString("UPDATE_MANIFEST_URL", "fluxUpdateUrl", updateChannel.getProperty("url", ""))
        updateString("UPDATE_PUBLIC_KEY", "fluxUpdatePublicKey", updateChannel.getProperty("publicKey", ""))
        updateString("UPDATE_CHANNEL", "fluxUpdateChannel", updateChannel.getProperty("channel", "stable"))
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    signingConfigs {
        if (releaseStore != null) create("distribution") {
            storeFile = file(releaseStore)
            storePassword = providers.environmentVariable("FLUX_SIGNING_STORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("FLUX_SIGNING_ALIAS").get()
            keyPassword = providers.environmentVariable("FLUX_SIGNING_KEY_PASSWORD").get()
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseStore != null) signingConfig = signingConfigs.getByName("distribution")
        }
        create("preview") {
            // Full, debug-signed install over the existing app, without IDE preview tooling.
            initWith(getByName("debug"))
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            matchingFallbacks += listOf("release")
        }
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
    testBuildType = providers.gradleProperty("fluxTestBuildType").getOrElse("debug")
    sourceSets["main"].apply {
        assets.srcDir(layout.buildDirectory.dir("generated/licenseAssets"))
        java.srcDir(layout.buildDirectory.dir("generated/uniffi"))
        jniLibs.srcDir(layout.buildDirectory.dir("generated/jniLibs"))
    }
    sourceSets["androidTest"].assets.srcDir("schemas")
}
kotlin { jvmToolchain(17) }
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

val buildRust by tasks.registering(Exec::class) {
    group = "build"
    description = "Build both Android ABIs and generate Kotlin bindings (Windows)."
    workingDir(rootDir)
    commandLine("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
        "-File", "scripts/build-rust.ps1")
    inputs.files(rootProject.fileTree("rust-core") {
        exclude("target/**")
    }, rootProject.file("rust-toolchain.toml"), rootProject.file("scripts/build-rust.ps1"),
        rootProject.file("scripts/env.ps1"))
    outputs.dirs(layout.buildDirectory.dir("generated/uniffi"),
        layout.buildDirectory.dir("generated/jniLibs"))
}
tasks.named("preBuild") { dependsOn(buildRust, "generateLicenseAssets") }
tasks.withType<Test>().configureEach {
    dependsOn("generateLicenseAssets")
    systemProperty("flux.licenseAssetsDir", layout.buildDirectory.dir("generated/licenseAssets").get().asFile.absolutePath)
}

dependencies {
    implementation("org.jsoup:jsoup:1.21.2")
    // BSDIFF40 BZip2 streams; no native patch runtime or additional network stack.
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("net.lingala.zip4j:zip4j:2.11.5")
    implementation(libs.room.runtime)
    implementation(libs.sqlcipher)
    ksp(libs.room.compiler)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    implementation(libs.lifecycle.viewmodel.navigation3)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.kotlinx.serialization.core)
    implementation("${libs.jna.get()}@aar")
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso)
}
