### Task 2: App Module Skeleton & Manifest

**Files:**
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: Root build environment.
- Produces: The core Android app module targeting API 27, with required permissions.

- [ ] **Step 1: Create app module build script**

```kotlin
// app/build.gradle.kts
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ryanpudd.photobooth"
    compileSdk = 33
    // Set a pinned NDK version for reproducibility.
    ndkVersion = "25.1.8937393" 

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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}
```

- [ ] **Step 2: Create AndroidManifest.xml**

```xml
<!-- app/src/main/AndroidManifest.xml -->
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-feature android:name="android.hardware.camera" android:required="false" />
    <uses-feature android:name="android.hardware.usb.host" />
    
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

    <application
        android:allowBackup="false"
        android:icon="@mipmap/ic_launcher"
        android:label="Wedding Photobooth"
        android:theme="@style/Theme.AppCompat.Light.NoActionBar">
        
        <!-- We will add Activity and Receivers in subsequent tasks -->
        
    </application>
</manifest>
```

- [ ] **Step 3: Commit**

```bash
git add app/build.gradle.kts app/src/main/AndroidManifest.xml
git commit -m "feat: configure app module build and permissions"
```
