import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ejao.proxy"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ejao.proxy"
        minSdk = 26
        targetSdk = 34
        // Overridable from CI via -PappVersion (no leading v, e.g. 1.101) and
        // -PversionCode (e.g. 10101 = major * 10000 + minor) so the
        // keep-alive heartbeat and panel report the exact release tag.
        versionCode = (project.findProperty("versionCode") as? String)?.toIntOrNull() ?: 1
        val appVersion = (project.findProperty("appVersion") as? String) ?: "1.101"
        versionName = appVersion
    }

    signingConfigs {
        // Fixed release key so every CI build shares one signature and updates
        // install in place (no more "package conflicts with an existing package").
        // Keystore is supplied via CI secrets; locally the env vars are absent
        // and the default debug key is used instead, which is fine for dev.
        create("ci") {
            val b64 = System.getenv("EJAO_KEYSTORE_BASE64")
            if (!b64.isNullOrEmpty()) {
                val keyFile = File(project.rootDir, "ejao-release-key.jks")
                if (!keyFile.exists()) {
                    keyFile.writeBytes(Base64.getDecoder().decode(b64))
                }
                storeFile = keyFile
                storePassword = System.getenv("EJAO_STORE_PASSWORD") ?: ""
                keyAlias = System.getenv("EJAO_KEY_ALIAS") ?: ""
                keyPassword = System.getenv("EJAO_KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (!System.getenv("EJAO_KEYSTORE_BASE64").isNullOrEmpty()) {
                signingConfig = signingConfigs.getByName("ci")
            }
        }
    }
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
