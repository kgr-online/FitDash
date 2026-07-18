plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fitdash"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.fitdash"
        minSdk = 28
        targetSdk = 36
        versionCode = 4
        versionName = "5.1.3"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    @Suppress("DEPRECATION")
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources {
            excludes += "META-INF/DEPENDENCIES"
        }
    }

    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "FitDash-${defaultConfig.versionName}-${name}.apk"
        }
    }
}

dependencies {
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    implementation("com.google.android.gms:play-services-auth:21.5.1")
    implementation("com.google.api-client:google-api-client-android:2.9.0")
    implementation("com.google.apis:google-api-services-drive:v3-rev20230822-2.0.0")
}
