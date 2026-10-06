plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

// Build flags (all optional):
//   -Pabi=arm64-v8a|x86_64   only package one ABI (phone: arm64-v8a, emulator: x86_64)
//   -Pbackend=fake|real      default printer backend on first launch (runtime toggle overrides)
//   -PappId=<id>             override applicationId (docs/USAGE.md)
//   -PbuildPython=<path>     Python 3.13 interpreter used at build time (default: python3.13 on PATH)
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

// Embedded Python version. 3.13 (not 3.14) because Chaquopy's package index only has Android
// builds of some native packages (e.g. `cryptography`) up to cp313.
val pythonVersion = "3.13"

// Build-time interpreter: must have the SAME major.minor as `pythonVersion` (Chaquopy uses it
// to compile .py -> .pyc). Override with -PbuildPython=/path/to/python3.13; default: `python3.13`
// on PATH (e.g. installed with `uv python install 3.13`).
val buildPythonCmd: String = (project.findProperty("buildPython") as String?) ?: "python$pythonVersion"
fun resolveOnPath(cmd: String): File? {
    if (cmd.contains(File.separatorChar)) return File(cmd).takeIf { it.canExecute() }
    return System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)
        .map { File(it, cmd) }.firstOrNull { it.isFile && it.canExecute() }
}
val buildPythonExe: File = resolveOnPath(buildPythonCmd) ?: throw GradleException(
    "Python $pythonVersion build interpreter '$buildPythonCmd' not found. Install Python $pythonVersion " +
    "(for example `uv python install $pythonVersion`, which puts python$pythonVersion in ~/.local/bin) " +
    "or pass -PbuildPython=/path/to/python$pythonVersion.")

chaquopy {
    defaultConfig {
        version = pythonVersion
        buildPython(buildPythonExe.absolutePath)
    }
}

dependencies {
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("junit:junit:4.13.2")
}
