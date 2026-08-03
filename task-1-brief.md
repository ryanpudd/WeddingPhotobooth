### Task 1: Project Initialization & Root Build Scripts

**Files:**
- Create: `build.gradle.kts`
- Create: `settings.gradle.kts`
- Create: `gradle.properties`

**Interfaces:**
- Consumes: None
- Produces: A functional root gradle project that can compile submodules.

- [ ] **Step 1: Create root build settings**

```kotlin
// settings.gradle.kts
rootProject.name = "WeddingPhotobooth"
include(":app")
// We will include the uvccamera module in a later task or configure it based on user feedback.
```

- [ ] **Step 2: Create root gradle config**

```kotlin
// build.gradle.kts
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.1.0")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.0")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}
```

- [ ] **Step 3: Create gradle properties**

```properties
# gradle.properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
```

- [ ] **Step 4: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle.properties
git commit -m "build: setup root gradle configuration"
```
