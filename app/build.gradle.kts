import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
}

android {
    namespace = "com.mxszty.teralis"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.mxszty.teralis"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "1.0.4"
        resourceConfigurations += listOf("zh", "en")
        vectorDrawables.useSupportLibrary = true
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

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// AGP 9 内置 Kotlin：KGP 由 AGP 自动提供，仅需指定字节码目标。
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.core:core-splashscreen:1.0.1")

    implementation("com.google.android.material:material:1.12.0")

    implementation("com.google.code.gson:gson:2.11.0")
}
