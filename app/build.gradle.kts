plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Version comes from CI (run number) so every release on main updates in place.
val buildNumber = (System.getenv("BUILD_NUMBER") ?: "1").toInt()

android {
    namespace = "io.github.adamstottdev.eufytv"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.adamstottdev.eufytv"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
        // The embedded Node.js runtime is only built for 64-bit ARM (SHIELD and most current TV boxes).
        ndk { abiFilters += "arm64-v8a" }
    }

    // The Node runtime is loaded from the app's native library folder, so keep libraries as real files.
    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/bridge"))

    signingConfigs {
        create("release") {
            val ks = System.getenv("SIGNING_KEYSTORE_PATH")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = System.getenv("SIGNING_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (System.getenv("SIGNING_KEYSTORE_PATH") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Bundles bridge/ (the Eufy connection, run by the embedded Node.js) into the app's assets.
val bundleBridge by tasks.registering(Exec::class) {
    val bridgeDir = rootProject.file("bridge")
    val outDir = layout.buildDirectory.dir("generated/bridge/eufy-bridge")
    inputs.dir(bridgeDir.resolve("src"))
    inputs.files(bridgeDir.resolve("package.json"), bridgeDir.resolve("package-lock.json"), bridgeDir.resolve("build.mjs"))
    outputs.dir(outDir)
    workingDir = bridgeDir
    val windows = System.getProperty("os.name").lowercase().contains("windows")
    val shell = if (windows) listOf("cmd", "/c") else listOf("sh", "-c")
    commandLine(shell + "npm ci --no-audit --no-fund && node build.mjs \"${outDir.get().asFile.absolutePath}\"")
}
tasks.named("preBuild") { dependsOn(bundleBridge) }

dependencies {
    // Node.js for Android (in-process, arm64) and its loader. MIT licensed.
    implementation("io.github.fellow99:node-bridge:0.2.0")
    runtimeOnly("io.github.fellow99:node-runtime:24.22.1")
    // QR code for signing in from a phone.
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
}
