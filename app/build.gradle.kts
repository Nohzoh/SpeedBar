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
        versionCode = 2
        versionName = "0.2.0"
    }

    // Signature : variables d'env (secrets GitHub) si définies,
    // sinon clé TEMPORAIRE du dossier signing/ (voir signing/README.md).
    signingConfigs {
        create("release") {
            val envStore = System.getenv("SIGNING_KEYSTORE_PATH")
            storeFile = if (!envStore.isNullOrEmpty()) file(envStore)
                        else rootProject.file("signing/speedbar-temp.jks")
            storePassword = System.getenv("SIGNING_STORE_PASSWORD")?.takeIf { it.isNotEmpty() } ?: "dwWELF4VSEQB3tH51B7z7rnK"
            keyAlias = System.getenv("SIGNING_KEY_ALIAS")?.takeIf { it.isNotEmpty() } ?: "speedbar"
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD")?.takeIf { it.isNotEmpty() } ?: "dwWELF4VSEQB3tH51B7z7rnK"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
