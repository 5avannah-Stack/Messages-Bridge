import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The signing key lives outside Git, in the project's Backups folder.
val signingFile = rootProject.file("../Backups/Release Signing/release-signing.properties")
val signing = Properties().apply { if (signingFile.exists()) signingFile.inputStream().use(::load) }

android {
    namespace = "cz.teous.messages.bridge"
    compileSdk = 36
    // gomobile builds the Go networks with this NDK.
    ndkVersion = "27.1.12297006"

    defaultConfig {
        applicationId = "cz.teous.messages.bridge"
        // 29 is the default for T's apps (`phone-apps`). Newer APIs sit behind `Build.VERSION.SDK_INT` checks.
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (signingFile.exists()) {
            create("release") {
                storeFile = signingFile.resolveSibling(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (signingFile.exists()) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            // T's phones and everyone's in TStore are arm64; the network libraries are native and large per ABI.
            ndk { abiFilters += "arm64-v8a" }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

/*
 * WhatsApp is the Go module in networks/whatsapp, bound into an AAR with gomobile. Go comes from PATH, or from the
 * environment variable GO; gomobile and gobind are built from the module's own go.mod, so their versions are pinned.
 * Releases carry arm64 only; -PgoTargets=android/arm64,android/amd64 adds the emulator's ABI.
 */
val goModule = rootProject.file("../networks/whatsapp")
val goBuild = layout.buildDirectory.dir("go")
val goExe = providers.environmentVariable("GO").orElse("go")
val goTargets = providers.gradleProperty("goTargets").orElse("android/arm64")
val exe = if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""

val goMobileTools = tasks.register<Exec>("goMobileTools") {
    val bin = goBuild.map { it.dir("bin") }
    inputs.files(File(goModule, "go.mod"), File(goModule, "go.sum"))
    outputs.dir(bin)
    workingDir = goModule
    commandLine(goExe.get(), "build", "-o", bin.get().asFile.path + File.separator, "golang.org/x/mobile/cmd/gomobile", "golang.org/x/mobile/cmd/gobind")
}

val whatsappAar = tasks.register<Exec>("whatsappAar") {
    dependsOn(goMobileTools)
    val aar = goBuild.map { it.file("whatsapp.aar") }
    inputs.files(fileTree(goModule) { include("*.go", "go.mod", "go.sum") })
    inputs.property("targets", goTargets)
    outputs.file(aar)
    workingDir = goModule
    val bin = goBuild.get().dir("bin").asFile
    val goDir = File(goExe.get()).parentFile
    val path = listOfNotNull(bin.path, File(System.getProperty("java.home"), "bin").path, goDir?.path, System.getenv("PATH"))
    environment("PATH", path.joinToString(File.pathSeparator))
    environment("ANDROID_HOME", androidComponents.sdkComponents.sdkDirectory.get().asFile.path)
    environment("ANDROID_NDK_HOME", androidComponents.sdkComponents.ndkDirectory.get().asFile.path)
    environment("GOTOOLCHAIN", "local")
    // -trimpath and -s -w keep build paths and symbols out. Kotlin sees the package as
    // cz.teous.messages.bridge.whatsapp.go.whatsapp (gomobile appends the Go package's name).
    commandLine(
        File(bin, "gomobile$exe").path, "bind", "-target=${goTargets.get()}", "-androidapi", "29", "-trimpath", "-ldflags=-s -w",
        "-javapkg", "cz.teous.messages.bridge.whatsapp.go", "-o", aar.get().asFile.path, ".",
    )
}

dependencies {
    implementation(files(goBuild.map { it.file("whatsapp.aar") }).builtBy(whatsappAar))
    implementation(project(":contract"))
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation:1.9.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.work:work-runtime:2.10.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4:1.9.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.9.0")
}
