import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

android {
    ndkVersion = "30.0.14904198"

    // Load local.properties
    val localProperties = Properties()
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localProperties.load(FileInputStream(localPropertiesFile))
    }

    signingConfigs {
        create("Release") {
            storeFile = file(localProperties.getProperty("RELEASE_STORE_FILE") ?: "")
            storePassword = localProperties.getProperty("RELEASE_STORE_PASSWORD")
            keyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS")
            keyPassword = localProperties.getProperty("RELEASE_KEY_PASSWORD")
        }
    }

    namespace = "com.edukreasi.Exam"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.edukreasi.Exam"
        minSdk = 23
        targetSdk = 35
        versionCode = 10
        versionName = "3.7.1 Beta"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val supabaseKey = localProperties.getProperty("SUPABASE_ANON_KEY", "").removeSurrounding("\"")
        buildConfigField(
            "String",
            "SUPABASE_ANON_KEY",
            "\"$supabaseKey\""
        )
        signingConfig = signingConfigs.getByName("Release")

        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a"))
        }

        resConfigs("en", "id")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("Release")

            ndk {
                debugSymbolLevel = "NONE"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += setOf(
                "META-INF/proguard/androidx-*.pro",
                "META-INF/services/**",
                "kotlin/**",
                "DebugProbesKt.bin",
                "META-INF/*.version",
                "META-INF/*.kotlin_module",
                "org/sqlite/**"
            )
        }
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    implementation("com.google.android.gms:play-services-mlkit-barcode-scanning:18.3.0")

    val camerax_version = "1.5.3"
    implementation("androidx.camera:camera-core:$camerax_version")
    implementation("androidx.camera:camera-camera2:$camerax_version")
    implementation("androidx.camera:camera-lifecycle:$camerax_version")
    implementation("androidx.camera:camera-view:$camerax_version")

    implementation(platform("com.google.firebase:firebase-bom:33.12.0"))
    implementation ("com.google.firebase:firebase-auth-ktx")
    implementation ("com.google.android.gms:play-services-auth:21.0.0")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.9.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.1")
    implementation("androidx.webkit:webkit:1.7.0")
    implementation ("androidx.browser:browser:1.8.0")
    implementation ("com.google.androidbrowserhelper:androidbrowserhelper:2.5.0")
    
    // HTTP Client
    implementation("com.squareup.okhttp3:okhttp:4.11.0")
    implementation("com.google.code.gson:gson:2.10.1")
    
    // Encryption untuk token & sensitive data
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // ✅ WorkManager untuk Sinkronisasi Latar Belakang
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    implementation("com.opencsv:opencsv:5.7.1") {
        exclude(group = "commons-logging", module = "commons-logging")
    }
    testImplementation("junit:junit:4.13.2")
}
