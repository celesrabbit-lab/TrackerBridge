import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Firma de las versiones oficiales. keystore.properties no va en git: sin ese archivo (o con la
// contrasena sin completar) la version release se firma con la clave de depuracion.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile")?.let { file(it).exists() } == true &&
    keystoreProps.getProperty("storePassword")?.startsWith("ESCRIBE_AQUI") == false

android {
    namespace = "dev.rabbit.trackerbridge"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "dev.rabbit.trackerbridge"
        minSdk = 29
        targetSdk = 34
    }

    // Una APK por visor. El codigo es comun; el permiso y el manifiesto de cada visor van en src/<visor>/
    flavorDimensions += "headset"
    productFlavors {
        create("quest") {
            dimension = "headset"
            versionCode = 9
            versionName = "0.7.0"
        }
        create("pico") {
            dimension = "headset"
            // App separada (dev.rabbit.trackerbridge.pico): se instala junto a la de Quest sin conflictos
            applicationIdSuffix = ".pico"
            versionCode = 1
            versionName = "0.1.0"
        }
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Version final (no depurable): Android la optimiza y gasta menos CPU
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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

dependencies {
    testImplementation("junit:junit:4.13.2")
}
