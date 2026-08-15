plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val ciKeystorePath = System.getenv("CI_ANDROID_KEYSTORE_PATH")

android {
    namespace = "com.localbabymonitor.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.localbabymonitor.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 14
        versionName = "0.6.6"
    }

    signingConfigs {
        if (!ciKeystorePath.isNullOrBlank()) {
            create("ci") {
                storeFile = file(ciKeystorePath)
                storePassword = requireNotNull(System.getenv("ANDROID_SIGNING_STORE_PASSWORD")) {
                    "ANDROID_SIGNING_STORE_PASSWORD is required for CI signing"
                }
                keyAlias = requireNotNull(System.getenv("ANDROID_SIGNING_KEY_ALIAS")) {
                    "ANDROID_SIGNING_KEY_ALIAS is required for CI signing"
                }
                keyPassword = requireNotNull(System.getenv("ANDROID_SIGNING_KEY_PASSWORD")) {
                    "ANDROID_SIGNING_KEY_PASSWORD is required for CI signing"
                }
            }
        }
    }

    buildTypes {
        debug {
            if (!ciKeystorePath.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("ci")
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    lint {
        baseline = file("lint-baseline.xml")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
