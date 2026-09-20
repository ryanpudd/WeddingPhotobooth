plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val uvccRoot: String = gradle.extra["uvccRoot"] as String

android {
    namespace = "com.ryanpudd.photobooth"
    compileSdk = 33
    // Pin exact NDK version for build reproducibility
    ndkVersion = "25.2.9519653" 

    defaultConfig {
        applicationId = "com.ryanpudd.photobooth"
        minSdk = 27
        targetSdk = 27
        versionCode = 1
        versionName = "1.0"
        
        ndk {
            abiFilters.add("armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // S3 background upload + encrypted on-device credential storage
    implementation("com.amazonaws:aws-android-sdk-s3:2.81.1")
    implementation("androidx.security:security-crypto:1.0.0")

    testImplementation("junit:junit:4.13.2")

    // libuvccamera
    debugImplementation(files("$uvccRoot/libuvccamera/build/outputs/aar/libuvccamera-debug.aar"))
    releaseImplementation(files("$uvccRoot/libuvccamera/build/outputs/aar/libuvccamera-release.aar"))

    // libuvccamera
    debugImplementation(files("$uvccRoot/usbCameraCommon/build/outputs/aar/usbCameraCommon-debug.aar"))
    releaseImplementation(files("$uvccRoot/usbCameraCommon/build/outputs/aar/usbCameraCommon-release.aar"))
    // Can't do this as UVCCamera is too old
    //implementation(":libuvccamera")
    implementation(files("../libs/common-2.12.4.aar"))
}
