plugins {
    id("com.android.application")
}

android {
    namespace = "com.lgmediabridge"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lgmediabridge"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        resourceConfigurations += listOf("en")
    }

    // The app intentionally has zero third-party dependencies: every subsystem
    // (HTTP/1.1 server, SSDP, UPnP AV, DLNA headers, webOS SSAP, WebSocket,
    // JSON) is implemented here against framework APIs only. That keeps the
    // transport stack auditable, keeps the APK small and removes supply-chain
    // and version-skew risk from the streaming path.
    dependencies {}

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Debug keystore keeps `assembleRelease` usable out of the box for
            // local testing; replace with a real signingConfig for distribution.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        buildConfig = false
        resValues = false
    }

    packaging {
        resources.excludes += setOf("META-INF/*")
    }

    lint {
        abortOnError = false
    }
}
