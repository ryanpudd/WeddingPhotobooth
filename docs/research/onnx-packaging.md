# ONNX Runtime dependency and packaging for `photoframe`

Research for [#15](https://github.com/ryanpudd/WeddingPhotobooth/issues/15), part of the map [#3](https://github.com/ryanpudd/WeddingPhotobooth/issues/3). Checked on 2026-11-02.

**Question:** how does the plain Kotlin/JVM module `photoframe-core` depend on ONNX Runtime (ORT) so that the Windows CLI now and the Android app later can each bring their own ORT artifact? What do ORT's native DLLs allow for packaging the CLI? Where do the model files (RF-DETR Nano, RTMPose) go?

Builds on [#4](https://github.com/ryanpudd/WeddingPhotobooth/issues/4) (ORT Java API + RF-DETR Nano) and [#6](https://github.com/ryanpudd/WeddingPhotobooth/issues/6) (plain JVM module `photoframe-core`, Java 17, no KMP).

## Recommendation

1. **Dependency: `compileOnly` in `photoframe-core`, `implementation` in each app.** Core compiles against `com.microsoft.onnxruntime:onnxruntime` but does not pass it on. Core's tests use the same artifact through `testImplementation`. The CLI adds `onnxruntime` and the Android app adds `onnxruntime-android`, both at one shared version. Two real projects use exactly this layout (see [Real projects](#what-real-projects-do)). Keep ORT types out of core's public API, so the apps never have to compile against them.
2. **Packaging: the Gradle `application` distribution** (`installDist` / `distZip`), with the three win-x64 DLLs copied into a `native/` folder and `onnxruntime.native.path` pointing at it. This avoids ORT's default "extract to %TEMP%" path, which **leaves a 17 MB folder behind on every run on Windows** (measured). Later, if the booth laptop should not need Java installed, wrap the same files in a **jpackage app-image**, which is the documented way to do that (`$APPDIR`). A **fat jar** works but has the temp-folder leak. **GraalVM native image is not worth it**: ORT's own native start-up (about 0.7 s) dwarfs the JVM's (about 0.05 s), and its JNI metadata would have to be built and maintained by hand.
3. **Model files: kept next to the tool**, in a `models/` folder inside the distribution, and loaded by file path. Do not put them in the jar or download them on first run. Do not commit them to git either: the fp32 RF-DETR Nano file is 108,074,865 bytes, which is **over GitHub's 100 MiB file limit**. A Gradle task fetches each model from a pinned URL and checks its SHA-256.

## Facts that shape the answer

Checked by downloading and listing the artifacts from Maven Central (version 1.31.1, released 2026-10-26 for all three of `onnxruntime`, `onnxruntime_gpu` and `onnxruntime-android`):

| | `onnxruntime` (JVM jar) | `onnxruntime-android` (AAR) |
|---|---|---|
| Download size | 48.6 MB (50,942,631 bytes) | 52.6 MB |
| Java classes | 71 in `ai.onnxruntime.*`, class files for Java 8 (major 52) | 84: the same 70 class names (all except `OrtSession$1`), plus `BuildConfig`, `TelemetryInitializer` and `ai.onnxruntime.telemetry.*`. Class files for Java 17 (major 61) |
| Public API | `javap -public` of `OrtEnvironment`, `OrtSession`, `OrtSession$SessionOptions` and `OnnxTensor` is **identical** in both | |
| Native libraries | Inside the jar as resources under `ai/onnxruntime/native/<os-arch>/`: win-x64 (`onnxruntime.dll` 17.3 MB, `onnxruntime4j_jni.dll`, `onnxruntime_providers_shared.dll`; 6.2 MB compressed), linux-x64, linux-aarch64, osx-aarch64. The osx folder also ships **57 MB of `.dSYM` debug files**. | `jni/<abi>/*.so` for arm64-v8a, armeabi-v7a, x86, x86_64. AGP packages them like any other AAR native library. |
| Dependencies | None (POM has no `<dependencies>`; [java/README.md](https://github.com/microsoft/onnxruntime/blob/v1.31.1/java/README.md): "The Java API does not have any runtime or compile dependencies") | None |
| Gradle module metadata | The POM says "published-with-gradle-metadata", but `onnxruntime-1.31.1.module` returns **404**. So no Gradle capabilities are declared, and **Gradle cannot tell that the jar and the AAR clash.** | |
| JPMS | No `module-info`. `Automatic-Module-Name: com.microsoft.onnxruntime` | |
| JDK modules needed | `jdeps`: `java.base` and `java.logging` only | |

### How ORT finds its native library on the JVM

From [`OnnxRuntime.java` at v1.31.1](https://github.com/microsoft/onnxruntime/blob/v1.31.1/java/src/main/java/ai/onnxruntime/OnnxRuntime.java) and the [package javadoc](https://github.com/microsoft/onnxruntime/blob/v1.31.1/java/src/main/java/ai/onnxruntime/package-info.java). The order, for each of `onnxruntime` and `onnxruntime4j_jni`:

1. If `onnxruntime.native.<lib>.skip=true`, skip it (you load it yourself).
2. If **`onnxruntime.native.path`** is set, `System.load(<dir>/<lib file>)`. It throws if the file is missing.
3. If `onnxruntime.native.<lib>.path` is set, load that one file.
4. Otherwise copy the resource `/ai/onnxruntime/native/<os-arch>/<file>` into a new `Files.createTempDirectory("onnxruntime-java")` and `System.load` it. If there is no such resource, fall back to `System.loadLibrary` (`java.library.path`).

The temp files are only marked `deleteOnExit()`. On Windows a loaded DLL cannot be deleted, so **the files stay**. I ran a probe three times with the plain jar on the class path (Temurin 21.0.7, the project's toolchain). Each run left a new `%TEMP%\onnxruntime-java<random>\` folder holding 17 MB (`onnxruntime.dll` + `onnxruntime4j_jni.dll`). With `-Donnxruntime.native.path=<dir>`, three more runs left nothing behind. An open upstream request asks for a configurable extraction folder ([#27656](https://github.com/microsoft/onnxruntime/issues/27656)). The maintainer's reply is that `onnxruntime.native.path` already covers it, and that re-using extracted files risks loading mismatched versions.

On Android, `isAndroid()` (`java.vendor == "The Android Project"`) short-circuits all of this to `System.loadLibrary`, so the AAR's `jni/` libraries are used and nothing is extracted.

### Windows: the JVM's own VC++ runtime matters

[The install docs](https://onnxruntime.ai/docs/install/) say "Windows builds require Visual C++ 2019 runtime. The latest version is recommended." In practice ORT is loaded into `java.exe`, which has already loaded the **`msvcp140.dll` shipped in the JDK's `bin/`**. Since ORT 1.21, an old copy there makes `onnxruntime.dll` fail with "A dynamic link library (DLL) initialization routine failed" ([#24287](https://github.com/microsoft/onnxruntime/issues/24287), [discussion #23971](https://github.com/microsoft/onnxruntime/discussions/23971); for example Temurin 8u402 fails and 8u492 works). The project's Temurin 21.0.7 loads ORT 1.31.1 fine (tested). Consequence: **the JDK that runs the CLI must be recent**, and a jpackage/jlink image freezes the build JDK's copy in place.

### Start-up cost (measured)

Laptop as in #4, Temurin 21.0.7, ORT 1.31.1, three runs each:

| Step | Time |
|---|---|
| JVM to `main` | about 45 ms |
| `OrtEnvironment.getEnvironment()`, DLLs extracted from the jar | 830–900 ms |
| The same with `onnxruntime.native.path` (no extraction) | 660–780 ms. `ORT_DISABLE_TELEMETRY=1` made no difference |
| `createSession(path)` for RF-DETR Nano fp32 (108 MB) | 470–510 ms (565–610 ms from a `byte[]`) |
| `createSession(path)` for RTMPose-m body7 (54 MB) | 135–165 ms |

So the CLI spends about 2 s before the first photo, and almost all of it is inside ORT's native code. That is small next to about 0.8 s per photo for detection plus pose (#4, [#5](https://github.com/ryanpudd/WeddingPhotobooth/issues/5)). Start-up time should not drive the packaging choice.

## Question 1: the dependency declaration

### Options

| | A. `compileOnly` in core, `implementation` in each app | B. `api` in core, Android app swaps it | C. Separate detection module |
|---|---|---|---|
| What it is | Core compiles against the JVM jar but doesn't pass it on ([Gradle](https://docs.gradle.org/current/userguide/java_library_plugin.html#sec:java_library_separation): `compileOnly` is for "dependencies which are required at compile time, but not at runtime") | Core exports `onnxruntime`. The app removes it with `exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime")` or a [dependency substitution rule](https://docs.gradle.org/current/userguide/resolution_rules.html#sec:dependency-substitution-rules) to `onnxruntime-android` | `photoframe-detect` holds the ORT code. Core stays free of ORT |
| JVM CLI | Adds `implementation(onnxruntime)` | Nothing to add | Same question again, one module down: A or B for `photoframe-detect` |
| Android app | Adds `implementation(onnxruntime-android)` | Must remember the exclude/substitution in **every** configuration that resolves core | As above |
| If you forget | `NoClassDefFoundError: ai/onnxruntime/...` the first time detection runs. It is loud and early, but it shows at run time, not at build time | The jar and the AAR both land on the Android classpath: 70 duplicate `ai.onnxruntime` classes (I listed both), plus 175 MB of desktop natives as Java resources. Because ORT publishes no Gradle capabilities, Gradle does not catch this. It should fail AGP's duplicate-class check, but I didn't build an Android app to confirm the exact error. | — |
| Core's tests | Need `testImplementation(onnxruntime)` (`compileOnly` is not on the test runtime) | Work as is | — |
| Who does this | [colonelpanic8/eva `keyword-core`](https://github.com/colonelpanic8/eva/blob/main/keyword-core/build.gradle.kts) and [Auto-doudizhu `core`](https://github.com/Superbaaaaber/Auto-doudizhu-on-Android-based-on-Douzero/blob/main/core/build.gradle.kts), both shared Kotlin/JVM cores with an Android app | [DJL](https://github.com/deepjavalibrary/djl/blob/97c6a0d69c1071ff3077434f9a6974da21364073/engines/onnxruntime/onnxruntime-android/build.gradle.kts): its Android engine module re-exports the JVM engine with `exclude("com.microsoft.onnxruntime", "onnxruntime")` and adds `api(libs.onnxruntimeAndroid)` | DJL also splits engine modules, but for a published multi-engine library |

### What real projects do

- **Option A**, in a shared core: eva's `keyword-core` has `compileOnly("com.microsoft.onnxruntime:onnxruntime:$onnxVersion")` plus `testImplementation` of the same. Its THIRD_PARTY notes say `onnxruntime-android` (app) and `onnxruntime` (desktop evaluation tool). Auto-doudizhu's `core` has the same pair, commented "Provided by onnxruntime-android in the app; the desktop jar has the same Java API". Its app has `implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")`. A GitHub code search for `compileOnly("com.microsoft.onnxruntime:onnxruntime` turns up a dozen more Kotlin `shared`/`core` modules doing the same.
- **Option B** is what DJL does. It makes sense for a library *published to Maven* for strangers, where the JVM users must get ORT without doing anything. In a single repo with two known consumers, the `compileOnly` route is simpler and nothing can leak.

### Recommendation

**Option A.** In `photoframe-core/build.gradle.kts`:

```kotlin
dependencies {
    // Each app brings its own ORT: onnxruntime (JVM) or onnxruntime-android. Same ai.onnxruntime API.
    compileOnly("com.microsoft.onnxruntime:onnxruntime:${property("ortVersion")}")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:${property("ortVersion")}")
}
```

In the CLI module, `implementation("com.microsoft.onnxruntime:onnxruntime:${property("ortVersion")}")`. Later, in `app/`, `implementation("com.microsoft.onnxruntime:onnxruntime-android:${property("ortVersion")}")`. Put `ortVersion=1.31.1` in the root `gradle.properties` so all three stay in step. The project has no version catalog today.

- Keep `OrtEnvironment`/`OrtSession`/`OnnxTensor` **internal** to core. The detector's public API takes a model location and returns plain Kotlin types (the subject rectangles from #5). Then `compileOnlyApi` isn't needed, and neither app compiles against ORT through core.
- A separate detection module (C) doesn't remove the problem; it moves it. Only split it out if core's own unit tests should run without ORT on the classpath.
- The AAR's classes target Java 17 and the jar's target Java 8. Both are fine for a Java 17 core.

## Question 2: packaging the Windows CLI

Sizes below exclude the models (about 162 MB; see [Question 3](#question-3-where-the-model-files-go)) and Kotlin's stdlib (about 1.7 MB).

| | Fat jar (Shadow) | Gradle `application` distribution | jpackage app-image (jlink inside) | GraalVM native image |
|---|---|---|---|---|
| What ships | One `photoframe.jar` | `bin/photoframe.bat`, `lib/*.jar`, plus our `native/` and `models/` | `photoframe.exe` launcher + a trimmed Java runtime + `app/` | `photoframe.exe` + ORT DLLs |
| Needs Java installed | Yes (17+, recent build) | Yes (17+, recent build; uses `JAVA_HOME` or `PATH`) | **No** | No |
| How ORT's DLLs are found | Extracted from the jar to `%TEMP%` on every run, and **17 MB left behind each run** (measured), unless we write our own extract-once cache and set `onnxruntime.native.path` | `onnxruntime.native.path` → `<app>/native` (measured: no extraction, nothing left behind) | `--java-options "-Donnxruntime.native.path=$APPDIR/native"` ([jpackage guide](https://docs.oracle.com/en/java/javase/21/jpackage/support-application-features.html): "Use the $APPDIR macro to reference resources included with the application") | From resources if the metadata lists them, or from the directory of the executable ([GraalVM JNI](https://www.graalvm.org/latest/reference-manual/native-image/dynamic-features/JNI/)) |
| Size | about 50 MB with every platform's natives. About 8 MB if Shadow excludes all but `win-x64` | about 49 MB ORT jar in `lib/` + 17 MB `native/` (6 MB zipped): a **57 MB zip** | the distribution + **74 MB** runtime (jlink of `java.base,java.logging,java.desktop` with jpackage's default options; 48 MB with `--compress=zip-6`). The JDK's `msvcp140.dll` is included | Not tried |
| Start-up | ORT init 0.83–0.9 s (extracting) | ORT init 0.66–0.78 s | 0.67 s ORT init in a jlinked runtime (measured) | The JVM part (about 45 ms) goes; ORT's 0.7 s stays |
| Known problems | Temp leak; and still needs a JDK whose `msvcp140.dll` is new enough | `applicationDefaultJvmArgs` is copied into `DEFAULT_JVM_OPTS` literally ([Gradle 9.6 template](https://github.com/gradle/gradle/blob/v9.6.0/platforms/jvm/plugins-application/src/main/resources/org/gradle/api/internal/plugins/windowsStartScript.txt)), so it can't say "app home". Set the property in `main` instead (below) | `jlink` alone refuses ORT: "automatic module cannot be used with jlink: com.microsoft.onnxruntime" (tested), so ORT must stay on the class path, which jpackage's `--input`/`--main-jar` does. An `.exe`/`.msi` installer needs WiX ([jpackage overview](https://docs.oracle.com/en/java/javase/21/jpackage/packaging-overview.html)); `--type app-image` doesn't | Building on Windows needs Visual Studio 2022 17.6+ / MSVC ([GraalVM on Windows](https://www.graalvm.org/latest/getting-started/windows/)). ORT's C code builds Java objects through JNI (`OnnxTensor`, `TensorInfo`, `OrtException` constructors in [`OrtJniUtil.c`](https://github.com/microsoft/onnxruntime/blob/v1.31.1/java/src/main/native/OrtJniUtil.c)). Each must be registered for JNI. The only shared metadata ([graalvm-reachability-metadata](https://github.com/oracle/graalvm-reachability-metadata/tree/master/metadata/com.microsoft.onnxruntime/onnxruntime)) is for ORT 1.20.0 only, covers linux-x64 resources only, marks only `java.lang.String` as JNI-accessible, and was AI-generated on 2026-10-03. It looks incomplete for real inference. The CLI's ImageIO adds `java.desktop` to the image as well |
| Verdict | Workable, not preferred | **Use now** | **Use when the booth laptop shouldn't need Java** | **Skip** |

### Recommendation

**Ship the Gradle `application` distribution now**, built with `installDist`/`distZip`:

```
photoframe/
  bin/photoframe.bat, bin/photoframe
  lib/photoframe.jar, photoframe-core.jar, onnxruntime-1.31.1.jar, kotlin-stdlib-*.jar
  native/onnxruntime.dll, onnxruntime4j_jni.dll, onnxruntime_providers_shared.dll
  models/rfdetr_nano.onnx, rtmpose-m_body7.onnx
```

- Copy `native/` out of the ORT jar at build time, so the DLLs always match the jar's classes. ORT's maintainer warns that mismatched natives cause "weird bugs" ([#27656](https://github.com/microsoft/onnxruntime/issues/27656)). A sketch (not built):

  ```kotlin
  distributions.main {
      contents {
          into("native") {
              from({ zipTree(configurations.runtimeClasspath.get().single { it.name.startsWith("onnxruntime-") }) }) {
                  include("ai/onnxruntime/native/win-x64/*.dll")
                  eachFile { path = name }
                  includeEmptyDirs = false
              }
          }
          into("models") { from(tasks.named("fetchModels")) }
      }
  }
  ```

  Files under `src/dist` are copied into the distribution as well ([Gradle application plugin](https://docs.gradle.org/current/userguide/application_plugin.html)).
- In `main`, before anything touches ORT, set the property when the folder exists:

  ```kotlin
  val appHome = Path.of(MainKt::class.java.protectionDomain.codeSource.location.toURI()).parent.parent // lib/..
  val native = appHome.resolve("native")
  if (System.getProperty("onnxruntime.native.path") == null && Files.isDirectory(native)) {
      System.setProperty("onnxruntime.native.path", native.toString())
  }
  ```

  Under `gradle run` and in tests there is no `native/` folder, so ORT falls back to extracting from the jar. That is fine for development.
- **Later, jpackage** turns the same `lib/`, `native/` and `models/` into an app-image with its own runtime. Use `--type app-image --win-console --input <lib+native+models> --main-jar photoframe.jar --add-modules java.base,java.logging,java.desktop --java-options "-Donnxruntime.native.path=$APPDIR/native"`. There's no installer and no WiX, and nothing to install on the laptop. Build it with a current JDK so the bundled `msvcp140.dll` is new enough.

## Question 3: where the model files go

| | In the jar (resources) | Next to the tool | Downloaded on first run |
|---|---|---|---|
| Loading | Read into a `byte[]`, then `createSession(byte[])`: a 108 MB heap copy, and 0.1 s slower (measured). ORT can't open a path inside a jar | `createSession(path)`, the [documented](https://onnxruntime.ai/docs/get-started/with-java.html) usual case | `createSession(path)` after the download |
| Android later | Doesn't carry over: Android uses assets, read as bytes or copied to `filesDir` | The same "core takes a location" API works with an asset copied to `filesDir` | Same |
| Risk at the wedding | None | None | Needs internet at the venue on first run; a failure blocks framing |
| Swapping models (fp32 ↔ int8, D-FINE-S fallback from #4) | Rebuild | Replace the file | Change the URL |

Sizes: RF-DETR Nano fp32 `onnx/model.onnx` is **108,074,865 bytes** (SHA-256 `9cbac6b1…7fee` at revision `eae21cee` of [onnx-community/rfdetr_nano-ONNX](https://huggingface.co/onnx-community/rfdetr_nano-ONNX)), int8 28.8 MB, fp16 54.4 MB. RTMPose-m body7 `end2end.onnx` is 54,330,655 bytes, inside a 50.8 MB zip from [OpenMMLab](https://github.com/open-mmlab/mmpose/tree/main/projects/rtmpose) (`rtmpose-m_simcc-body7_pt-body7_420e-256x192-e48f03d0_20230504.zip`). Both are single files with no external-data side files. Both loaded in the JVM probe above.

### Recommendation

**Next to the tool**, in `models/`, with a `--models <dir>` option that defaults to `<app home>/models`. Core's detector takes a model path (and optionally a `ByteArray` for Android later) and never knows where the file came from.

- **Don't commit the models.** [GitHub blocks files larger than 100 MiB](https://docs.github.com/en/repositories/working-with-files/managing-large-files/about-large-files-on-github), and the fp32 RF-DETR file is 103.07 MiB. Git LFS is possible but costs quota for no gain.
- **Fetch them at build time.** A `fetchModels` Gradle task downloads each file from a pinned URL (the Hugging Face revision hash for RF-DETR, the dated OpenMMLab zip for RTMPose), checks the SHA-256, and caches it outside `build/`. The distribution then includes them. The build needs the internet once; the booth never does.

## Open questions

1. **The Android build was not tried.** I didn't confirm AGP's exact error when both the jar and the AAR end up on the classpath (option B gone wrong). Nor did I check whether the AAR's `proguard.txt`, which keeps only the telemetry classes, is enough if the app ever turns on R8. The app doesn't minify today.
2. **GraalVM wasn't tried.** The verdict rests on the JNI surface in ORT's C code and on the state of the shared metadata, not on a failed build.
3. **The Gradle snippets are sketches.** The distribution layout, the `onnxruntime.native.path` behaviour and the jlink runtime were tested by hand with `java`/`jlink`, not through a Gradle build of the real modules.
4. **ORT environment start-up (about 0.7 s)** was not profiled. Telemetry didn't change it. It only matters if the CLI is ever run once per photo.
5. **Telemetry** (#4): `OrtEnvironment.setTelemetry(false)` exists on the JVM jar's `OrtEnvironment` (seen in `javap`), so core can call it on both platforms. Whether that stops the 1DS upload on Windows wasn't checked here.

## Sources

- Maven Central: [`onnxruntime` 1.31.1](https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime/1.31.1/) (jar, POM; `.module` is 404), [`onnxruntime-android` 1.31.1](https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.31.1/) (AAR: `classes.jar`, `jni/`, `proguard.txt`), `maven-metadata.xml` for `onnxruntime`, `onnxruntime_gpu` and `onnxruntime-android`. Contents listed with `unzip -l` and `javap -public`; module needs with `jdeps -s`.
- ONNX Runtime: [Java get-started](https://onnxruntime.ai/docs/get-started/with-java.html), [install requirements](https://onnxruntime.ai/docs/install/), [java/README.md "Advanced Loading"](https://github.com/microsoft/onnxruntime/blob/v1.31.1/java/README.md), [`OnnxRuntime.java`](https://github.com/microsoft/onnxruntime/blob/v1.31.1/java/src/main/java/ai/onnxruntime/OnnxRuntime.java), [`package-info.java`](https://github.com/microsoft/onnxruntime/blob/v1.31.1/java/src/main/java/ai/onnxruntime/package-info.java), [`OrtJniUtil.c`](https://github.com/microsoft/onnxruntime/blob/v1.31.1/java/src/main/native/OrtJniUtil.c), issues [#27656](https://github.com/microsoft/onnxruntime/issues/27656), [#27655](https://github.com/microsoft/onnxruntime/issues/27655), [#24287](https://github.com/microsoft/onnxruntime/issues/24287), [#5172](https://github.com/microsoft/onnxruntime/issues/5172) (an early native-image attempt that needed resource and reflection configs), [discussion #23971](https://github.com/microsoft/onnxruntime/discussions/23971).
- Gradle: [Java Library plugin, API/implementation separation](https://docs.gradle.org/current/userguide/java_library_plugin.html#sec:java_library_separation), [dependency substitution rules](https://docs.gradle.org/current/userguide/resolution_rules.html#sec:dependency-substitution-rules), [capabilities](https://docs.gradle.org/current/userguide/component_capabilities.html), [application plugin](https://docs.gradle.org/current/userguide/application_plugin.html), [Windows start script template at v9.6.0](https://github.com/gradle/gradle/blob/v9.6.0/platforms/jvm/plugins-application/src/main/resources/org/gradle/api/internal/plugins/windowsStartScript.txt). Shadow: `com.gradleup.shadow` 9.6.1 on the [Gradle Plugin Portal](https://plugins.gradle.org/plugin/com.gradleup.shadow).
- JDK 21: [jpackage man page](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jpackage.html), [jpackage guide: application features (`$APPDIR`)](https://docs.oracle.com/en/java/javase/21/jpackage/support-application-features.html), [packaging overview (WiX)](https://docs.oracle.com/en/java/javase/21/jpackage/packaging-overview.html), [jlink man page](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jlink.html).
- GraalVM: [JNI in Native Image](https://www.graalvm.org/latest/reference-manual/native-image/dynamic-features/JNI/), [Native Image on Windows](https://www.graalvm.org/latest/getting-started/windows/), [reachability metadata for `com.microsoft.onnxruntime:onnxruntime`](https://github.com/oracle/graalvm-reachability-metadata/tree/master/metadata/com.microsoft.onnxruntime/onnxruntime).
- Real projects: [colonelpanic8/eva `keyword-core`](https://github.com/colonelpanic8/eva/blob/main/keyword-core/build.gradle.kts), [Superbaaaaber/Auto-doudizhu `core`](https://github.com/Superbaaaaber/Auto-doudizhu-on-Android-based-on-Douzero/blob/main/core/build.gradle.kts) and [its app](https://github.com/Superbaaaaber/Auto-doudizhu-on-Android-based-on-Douzero/blob/main/app/build.gradle.kts), [DJL `onnxruntime-android`](https://github.com/deepjavalibrary/djl/blob/97c6a0d69c1071ff3077434f9a6974da21364073/engines/onnxruntime/onnxruntime-android/build.gradle.kts) and [`onnxruntime-engine`](https://github.com/deepjavalibrary/djl/blob/master/engines/onnxruntime/onnxruntime-engine/build.gradle.kts).
- Models: [onnx-community/rfdetr_nano-ONNX](https://huggingface.co/onnx-community/rfdetr_nano-ONNX) (file sizes and SHA-256 from the Hub API), [mmpose RTMPose](https://github.com/open-mmlab/mmpose/tree/main/projects/rtmpose) (ONNX SDK zip, size from HTTP `Content-Length` and `unzip -l`). [GitHub large-file limits](https://docs.github.com/en/repositories/working-with-files/managing-large-files/about-large-files-on-github).
- Measurements: scratch Java probes (not committed) on the laptop from #4, Temurin 21.0.7 from the project's Gradle toolchain, ORT 1.31.1 jar from Maven Central.
