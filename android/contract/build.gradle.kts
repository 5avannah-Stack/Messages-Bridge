// The contract between Messages and the bridge (docs/CONTRACT.md). Messages copies this module as it is: it holds only
// interface definitions and constants, under the MIT licence in this folder, so no GPL code crosses.
plugins {
    id("com.android.library")
}

android {
    namespace = "cz.teous.messages.bridge.contract"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
