plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

// Build flags (all optional):
//   -Pabi=arm64-v8a|x86_64   only package one ABI (phone: arm64-v8a, emulator: x86_64)
//   -Pbackend=fake|real      default printer backend on first launch (runtime toggle overrides)
//   -PappId=<id>             override applicationId (docs/USAGE.md)
val onlyAbi = project.findProperty("abi") as String?
val defaultBackend = (project.findProperty("backend") as String?) ?: "fake"
val appIdOverride = project.findProperty("appId") as String?

android {
    namespace = "com.javcabr.printerbridge"
    compileSdk = 35

    defaultConfig {
        applicationId = appIdOverride ?: "com.javcabr.printerbridge"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "DEFAULT_BACKEND", "\"$defaultBackend\"")
        ndk {
            abiFilters += if (onlyAbi != null) listOf(onlyAbi) else listOf("arm64-v8a", "x86_64")
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "META-INF/*" }
}

chaquopy {
    defaultConfig {
        version = "3.14"
        // Must be the same major.minor as `version`; compiles .py -> .pyc at build time.
        buildPython("/usr/bin/python3")
    }
}

dependencies {
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("junit:junit:4.13.2")
}
