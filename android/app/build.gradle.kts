import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Fixed company signing key, so every new APK installs over the old one and keeps the MR's data.
val signingProps = Properties().apply {
    val f = file("signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.akimoto.fieldtrack"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.akimoto.fieldtrack"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "1.0.1"
    }

    signingConfigs {
        create("company") {
            storeFile = file(signingProps.getProperty("storeFile", "akimoto-ft.jks"))
            storePassword = signingProps.getProperty("storePassword")
            keyAlias = signingProps.getProperty("keyAlias")
            keyPassword = signingProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("company")
        }
        debug {
            signingConfig = signingConfigs.getByName("company")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
        htmlReport = true
        xmlReport = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    testImplementation("junit:junit:4.13.2")
}
