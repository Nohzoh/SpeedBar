plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.nohzoh.speedbar"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.nohzoh.speedbar"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.4.0"
    }

    // Signature release : uniquement via variables d'env (secrets GitHub).
    // Sans elles, l'APK release n'est pas signé (le workflow échoue avec un message clair).
    val keystorePath = System.getenv("SIGNING_KEYSTORE_PATH")?.takeIf { it.isNotEmpty() }
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// Aucune dépendance : uniquement le SDK Android (LocationManager, Notification).
