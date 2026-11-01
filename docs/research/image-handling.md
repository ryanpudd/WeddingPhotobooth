# Image handling for `photoframe`: reading, compositing and writing on the JVM and Android

Research for [#6](https://github.com/ryanpudd/WeddingPhotobooth/issues/6), part of the map [#3](https://github.com/ryanpudd/WeddingPhotobooth/issues/3). Researched 2026-11-01.

**Question:** How should `photoframe` read photos, composite the crop into the border's window, and write the output, so that the core runs on the desktop JVM now and on Android later?

## Recommendation (TL;DR)

1. **Do the pixel work in pure Kotlin, in a plain Kotlin/JVM module** (`photoframe-core`, `kotlin("jvm")`, JVM target 17). It holds:
   - a `Raster` type: width, height and an `IntArray` of non-premultiplied sRGB `0xAARRGGBB` pixels;
   - crop, resize (Lanczos-3, with Catmull-Rom as a fallback), fill, and source-over alpha compositing.

   It must not import `java.awt`, `javax.imageio` or `android.*`.
2. **Put the platform seam at decode and encode only.** A small `ImageCodec` interface has two adapters:
   - the JVM adapter uses `javax.imageio` from the JDK, with no extra dependency;
   - a later Android adapter uses `BitmapFactory`, `Bitmap.getPixels`/`setPixels`, `Bitmap.compress` and androidx `ExifInterface`.
3. **Do not use Kotlin Multiplatform yet.** On this project's AGP 8.7.3 it means the `com.android.library` + `androidTarget()` route, which Google is deprecating. The newer plugin needs AGP 8.10. An Android app can consume a plain JVM module with `implementation(project(":photoframe-core"))`.
4. **No library gives the same results on both platforms at an acceptable cost:**
   - Java2D, ImageIO and TwelveMonkeys are AWT-based, and AWT isn't on Android.
   - Skiko has no Android artifact on Maven Central, and its README lists Android only for arm64 and x86_64. The booth app is built for `armeabi-v7a` only.
   - Android's `Bitmap`/`Canvas` only offers bilinear scaling.
   - OpenCV runs on both platforms, but it is a heavy native dependency with no built-in alpha-over operation. It is only worth it if the detection research ([#4](https://github.com/ryanpudd/WeddingPhotobooth/issues/4)) picks OpenCV anyway.

Why put the pixel work in Kotlin instead of behind a per-platform "draw" interface:

- **Identical output on both platforms.** Example-based tests of framed photos then hold on both.
- **Lanczos everywhere.** Android's public API has no bicubic or Lanczos filter, so a Canvas-based Android build would give softer 2x crops than the desktop build.
- **No native dependencies.**

The cost is about 150-250 lines of resampling and compositing code, plus some unmeasured CPU time (see [Open questions](#open-questions-and-uncertainties)).

## Project facts that constrain the choice

From `build.gradle.kts` and `app/build.gradle.kts` on `main`:

| Fact | Value | Why it matters |
|---|---|---|
| Android Gradle Plugin | 8.7.3 | Below the AGP 8.10.0 minimum of the new KMP Android plugin |
| Kotlin Gradle Plugin | 2.0.21 | Fine for a JVM module and for KMP |
| `compileSdk` / `minSdk` / `targetSdk` | 33 / 27 / 27 | Bitmap pixels live in the native heap (API 26+). androidx ExifInterface 1.5's minSdk 23 is fine |
| ABIs | `armeabi-v7a` only | Rules out Skiko on Android (arm64 and x86_64 only) |
| Java / JVM target | 17 | The core module should also target 17 |
| Existing image code | `MainActivity.kt` uses `Bitmap.createBitmap`, `Bitmap.compress(JPEG)` and `android.media.ExifInterface` for orientation | The Android adapter would follow the same APIs |

The test data was inspected with a small chunk lister, not taken on trust:

- **All 57 photos in `tmp/WeddingPhotobooth/`:** 1920x1080, 8-bit RGBA (PNG colour type 6). Each has exactly `IHDR`, `sRGB`, `sBIT`, `IDAT…` and `IEND` chunks, with no `eXIf`, `tEXt`, `iTXt` or `tIME`. **The inputs carry no EXIF or text metadata**, so there is nothing to keep today. The capture time is only in the file name.
- **Halloween border (`app/src/main/res/drawable-nodpi/border_overlay.png`):** 1920x1200, 8-bit RGBA, with no metadata chunks.

## Options compared

| | `javax.imageio` + Java2D | TwelveMonkeys | Skiko (Skia) | OpenCV | Android `Bitmap` + `Canvas` | Pure Kotlin on `IntArray` |
|---|---|---|---|---|---|---|
| Runs on desktop JVM | Yes, part of the JDK | Yes (3.15.3, Java 8+) | Yes (`skiko-awt-runtime-windows-x64` 0.9.18) | Via `org.bytedeco:opencv-platform` 4.14.0-1.5.14. `org.openpnp:opencv` stopped at 4.9.0-0 (Dec 2023) | No | Yes |
| Runs in this Android app | No (no `java.awt` on Android) | No (built on `java.awt.image`) | No: no Android artifact on Maven Central, and the README lists Android only for arm64/x86_64 | Yes, the official `org.opencv:opencv` AAR (latest 5.0.0; the 4.14.0 AAR is 123 MB with all ABIs) | Yes | Yes |
| Read PNG | Yes | Uses the JDK's | Yes | Yes | Yes | No, needs a codec |
| Write PNG and JPEG | Yes, but the JPEG writer refuses images with alpha (JDK 11+) | Adds JPEG extras (CMYK etc.) | Yes (`EncodedImageFormat` PNG, JPEG, WEBP) | Yes | Yes (`CompressFormat` JPEG, PNG, WEBP_*) | No, needs a codec |
| Upscaling filters | Nearest, bilinear, bicubic (no Lanczos) | Many, including Lanczos, Mitchell, Catmull-Rom (`ResampleOp`) | Bilinear, Mitchell, Catmull-Rom (`CubicResampler`) | `INTER_CUBIC`, `INTER_LANCZOS4` | **Bilinear or nearest only** | Whatever we write (Lanczos-3) |
| Alpha compositing | `AlphaComposite.SrcOver` | Uses Java2D | `BlendMode.SRC_OVER` | No single call. Split channels and blend by hand | Default `Paint` is source-over | ~20 lines |
| EXIF write | By hand: JPEG `APP1` as an `unknown` marker node. PNG `eXIf` only as a raw `UnknownChunk` | Reads EXIF (`imageio-metadata`) | No | `imwriteWithMetadata` (C++ 4.12+). The Java binding is unverified | Via androidx `ExifInterface` (JPEG, PNG `eXIf`, WebP) | Not applicable |
| Same output on both platforms | n/a | n/a | n/a | Probably (same C++ code) | n/a | **Yes** |

### Desktop: `javax.imageio` and Java2D

- **Formats.** The JDK has built-in plug-ins that read and write BMP, GIF, JPEG, PNG, TIFF and WBMP ([javax.imageio package summary][imageio-pkg]). No dependency is needed for our PNG input or our PNG/JPEG output.
- **Resampling.** `RenderingHints.KEY_INTERPOLATION` has only `NEAREST_NEIGHBOR`, `BILINEAR` and `BICUBIC`, with no Lanczos ([RenderingHints][rendering-hints]).
- **Compositing.** `AlphaComposite.SrcOver` is the standard Porter-Duff source-over ([AlphaComposite][alpha-composite]).
- **JPEG and alpha.** Since JDK 11 the JPEG writer no longer supports images with an alpha channel, and `ImageIO.write` "will now return `false`, meaning that it did not write the image" ([JDK-8204188][jdk-8204188]). Our photos decode as RGBA, so a JPEG encoder must first copy into an opaque `TYPE_INT_RGB` image. Otherwise the write fails silently.
- **JPEG EXIF.** The JPEG plug-in has no EXIF model. An `APP1` Exif segment shows up as a generic `unknown` marker node (`MarkerTag="225"`), and you write one by merging such a node into the native metadata tree ([JPEG metadata format][jpeg-metadata]). Copying EXIF bytes across is possible but low-level.
- **PNG EXIF.** The native PNG metadata format knows only the PNG 1.2 chunks. There is no `eXIf` element, and other chunks go under `UnknownChunks` ([PNG metadata format][png-metadata]). The JDK `PNGImageWriter` writes unknown chunks before `IDAT` (`writeUnknownChunks()` comes just before `write_IDAT`, [source][png-writer-src]), and that is where `eXIf` must go. So an `eXIf` chunk can probably be written by hand, but this is untested.

### Desktop: TwelveMonkeys

- **What it is.** A set of extra ImageIO plug-ins (JPEG, TIFF, WebP, PSD and more) plus `common-image`. Latest is 3.15.3 on Maven Central, and it needs Java 8+ ([README][tm-readme], [Maven metadata][tm-maven]).
- **No extra formats for us.** It doesn't add PNG, which the JDK already handles. Its JPEG reader mostly helps with odd JPEGs (CMYK, broken streams), which we don't have.
- **Resampling.** `ResampleOp` offers 15 filters, including `FILTER_LANCZOS`, `FILTER_MITCHELL` and `FILTER_CATROM` ([ResampleOp.java][tm-resample]). That would solve Lanczos on the desktop.
- **No Android.** It imports `java.awt.*` and `java.awt.image.*`, so it can't run on Android.

### Desktop (and maybe Android): Skiko

- **Platforms.** Skiko is JetBrains' Kotlin binding for Skia. The README lists JVM targets for Windows x64, macOS and Linux, and "Kotlin/JVM on Android (x86_64 and arm64), starting with API version 24" ([README][skiko-readme]).
- **Artifacts.** Maven Central has `skiko-awt-runtime-*` artifacts (latest 0.9.18, including `windows-x64`). Requests for `skiko-android`, `skiko-android-runtime-arm64` and `skiko-android-runtime-arm32` all return 404 ([Maven Central search][skiko-maven]).
- **Not usable in the booth app.** Even if an Android build exists elsewhere, the app ships `armeabi-v7a` only.
- **Capabilities.** It has good resampling: `SamplingMode.MITCHELL` = `CubicResampler(1/3, 1/3)` and `CATMULL_ROM` = `CubicResampler(0, 0.5)` ([SamplingMode.kt][skiko-sampling]). It encodes PNG, JPEG and WebP ([EncodedImageFormat.kt][skiko-encoded]). We found no API that writes EXIF.

### Both platforms: OpenCV

- **Android.** The official `org.opencv:opencv` artifact on Maven Central is an Android AAR (`<packaging>aar</packaging>`). The latest is 5.0.0, and the 4.14.0 AAR is 123 MB because it bundles every ABI ([POM][opencv-pom]).
- **Desktop.** You need a third-party build: `org.bytedeco:opencv-platform` 4.14.0-1.5.14, or `org.openpnp:opencv`, whose last release is 4.9.0-0 from Dec 2023 ([openpnp metadata][openpnp-maven]).
- **Resampling.** `resize` supports `INTER_CUBIC` and `INTER_LANCZOS4` ("Lanczos interpolation over 8x8 neighborhood"). The docs say enlarging "will generally look best with `INTER_CUBIC` (slow) or `INTER_LINEAR`" ([imgproc.hpp][opencv-imgproc]).
- **Metadata.** `imwriteWithMetadata` with `IMAGE_METADATA_EXIF` is in the C++ API from 4.12.0. It is absent in 4.11.0 ([imgcodecs.hpp 4.12.0][opencv-imgcodecs]). We did not check whether the Java bindings expose it.
- **Compositing.** There is no single "draw RGBA over" call. You split the channels and blend by hand.
- **Verdict.** The same `Mat` code runs on both platforms, but the cost is two different native distributions, a large APK and awkward compositing. It only makes sense if [#4](https://github.com/ryanpudd/WeddingPhotobooth/issues/4) picks OpenCV for detection. Even then, a decoded `Mat` can be turned into the core `Raster`.

### Android: `Bitmap` and `Canvas`

- **Scaling.** `Bitmap.createScaledBitmap(..., filter)` offers only bilinear ("If this is true then bilinear filtering will be used…") or nearest-neighbour ([Bitmap][android-bitmap]). `Paint.FILTER_BITMAP_FLAG` "enables bilinear sampling on scaled bitmaps", and hardware-accelerated drawing always uses bilinear ([Paint][android-paint]). **The public API has no bicubic or Lanczos.**
- **Pixel access.** `getPixels`/`setPixels` use "non-premultiplied ARGB values in the sRGB color space" ([Bitmap][android-bitmap]). That is the same packing as the proposed core `Raster`, so the adapter needs no conversion.
- **Encoding.** `compress(format, quality, stream)` treats quality as a "hint to the compressor, 0-100… interpreted differently depending on the CompressFormat" ([Bitmap][android-bitmap]). It doesn't write EXIF.
- **EXIF.** androidx `ExifInterface` writes JPEG, PNG and WebP. For PNG, "the Exif data will be stored as an "eXIf" chunk" ([ExifInterface][androidx-exif]). It is at 1.4.2 stable and 1.5.0-alpha01, which moves its default minSdk to 23 ([release notes][androidx-exif-releases]).
- **Memory.** From Android 8.0 (API 26), bitmap pixel data is stored in the native heap ([Managing bitmap memory][android-bitmap-memory]). A pure-Kotlin `IntArray` lives on the Java heap instead. A 3840x2400 raster is 36.9 MB (see open questions).

## Resampling quality for 2x crops

- **How big the upscale is.** At 2x zoom, a crop is about 960x540 source pixels. It is then scaled up to fill a window that can be over 3000 px wide, so the upscale is about 3-4x. Filter choice is visible.
- **Bicubic** (Java2D, Skia's Catmull-Rom/Mitchell, OpenCV `INTER_CUBIC`) is a reasonable baseline.
- **Lanczos-3** keeps edges a bit sharper, at the cost of slight ringing near hard edges. That is the usual trade-off; choose by eye in a spike.
- **Android** only offers bilinear, which is noticeably softer at these factors ([Paint][android-paint]).
- **Implementation in pure Kotlin.** A separable resampler (horizontal pass, then vertical) with precomputed per-column and per-row weights gives Lanczos-3 and Catmull-Rom from the same code. Only the kernel function differs.
- **Alpha.** The photos are opaque, so no premultiplication is needed when resizing them. If a border ever has to be resized (for example the 1312x816 wedding border, see [#8](https://github.com/ryanpudd/WeddingPhotobooth/issues/8)), premultiply first, or transparent edges will get dark fringes.

## Alpha compositing of the border

Order of drawing for a framed photo, and for each cell of a collage:

1. Allocate an output `Raster` at the border's size and fill it with the background colour from the border settings.
2. Draw each resized crop into its window or cell rectangle. The crop is opaque, so this is a copy.
3. Draw the border artwork over everything with source-over.

Source-over on non-premultiplied pixels, with an opaque destination:

`out = src.rgb * a + dst.rgb * (1 - a)`, with `a = src.alpha / 255`, and output alpha = 255.

Because steps 1-2 always make the destination opaque, the general formula with a non-opaque destination never comes up. The output is always opaque, which also suits JPEG.

## Writing PNG and JPEG, and EXIF

- **PNG.** ImageIO's PNG writer on the JVM, `Bitmap.compress(PNG)` on Android. Both are lossless and have no quality setting.
- **JPEG.**
  - JVM: convert to `TYPE_INT_RGB` before writing, because of JDK-8204188 (above). Set the quality through `ImageWriteParam.setCompressionQuality`.
  - Android: `Bitmap.compress(JPEG, quality)`. The app already uses this.
- **EXIF.** The test photos have none, so for v1 the core does not need to carry metadata. If it is wanted later:
  - The JVM adapter can copy an `APP1` segment, or write a PNG `eXIf` unknown chunk, as described above. Both are low-level and untested.
  - The Android adapter calls `ExifInterface.saveAttributes()` after `compress` (JPEG, PNG, WebP).
  - Either way, it belongs in the codec adapters, not the core.

## One library, or a seam?

- **No single library meets the bar.** None runs on both the desktop JVM and this `armeabi-v7a` Android app, offers better-than-bilinear scaling on Android, and stays light. OpenCV comes closest, at a heavy cost.
- **Option A: a per-platform "image ops" interface** (decode / crop / scale / draw / encode, implemented once with Java2D and once with Canvas). The platforms would give different pixels: bicubic vs bilinear, and different JPEG encoders. Crop and collage logic would have to be tested twice. Android would also be stuck with bilinear.
- **Option B: a codec-only seam with the pixel work in Kotlin** (recommended). One pixel pipeline, one set of tests, Lanczos everywhere. The platform adapters stay thin (decode to `IntArray`, `IntArray` to bytes) and are easy to test with a round trip.

### Proposed seam

```kotlin
// photoframe-core: plugins { kotlin("jvm") }, jvmTarget 17.
// No java.awt, javax.imageio or android.* imports.

/** Non-premultiplied sRGB pixels, packed 0xAARRGGBB, row-major. */
class Raster(val width: Int, val height: Int, val argb: IntArray = IntArray(width * height))

sealed interface OutputFormat {
    data object Png : OutputFormat
    data class Jpeg(val quality: Float = 0.92f) : OutputFormat
}

/** The only platform seam: bytes <-> Raster. */
interface ImageCodec {
    fun decode(input: java.io.InputStream): Raster
    fun encode(image: Raster, format: OutputFormat, output: java.io.OutputStream)
}

enum class ResampleFilter { LANCZOS3, CATMULL_ROM, BILINEAR }

// Pure-Kotlin pixel operations, the same on every platform.
fun Raster.crop(x: Int, y: Int, width: Int, height: Int): Raster
fun Raster.resize(width: Int, height: Int, filter: ResampleFilter = ResampleFilter.LANCZOS3): Raster
fun Raster.fill(argb: Int)
fun Raster.drawOver(source: Raster, x: Int, y: Int)   // source-over
```

- **Modules.**
  - `photoframe-core` holds the code above plus the crop, layout and collage rules.
  - `photoframe-cli` (JVM) holds `ImageIoCodec`.
  - Later, the Android app adds `implementation(project(":photoframe-core"))` and a `BitmapCodec`.
- **`java.io` streams.** Using them in the interface is fine, because Android has `java.io`. Swap them for `ByteArray` if the core ever moves to KMP `commonMain`.
- **Keeping the core platform-neutral.** Add a small test that fails if any `java.awt`, `javax.imageio` or `android.` import appears in `photoframe-core`. Android lint's `InvalidPackage` check can also catch references to packages Android doesn't have, but it is off by default ([lint check docs][lint-invalidpackage]).

## Kotlin Multiplatform vs a plain JVM module

- **A plain JVM module works in an Android app.** Android Studio's "Kotlin or Java library" builds "a traditional JAR file… useful… when you want to share code with other platforms". The trade-off is that it can't hold Android resources or manifests, which the core doesn't need ([Create an Android library][android-library]). The module's bytecode target must not be above what the app's build supports. The app compiles with Java 17 ([Java versions in Android builds][android-jdks]).
- **KMP with the new plugin needs a newer AGP.** The new `com.android.kotlin.multiplatform.library` plugin needs **AGP 8.10.0** and KGP 2.0.0 or higher. This project is on 8.7.3.
- **The older KMP route is going away.** The `com.android.library` + `androidTarget()` route "depends on Android Gradle Plugin APIs that are deprecated and require opt-in in Android Gradle plugin 9.0" and that "are expected to be removed in Android Gradle plugin 10.0" ([Android KMP plugin][android-kmp-plugin]).
- **We don't need `expect`/`actual`.** Kotlin's own guidance prefers interfaces over expect/actual classes when interfaces will do. Interfaces allow test fakes and more than one implementation, and expected/actual classes are still Beta ([expect/actual docs][kotlin-expect-actual]). The `ImageCodec` interface above is exactly that pattern, so KMP adds nothing we need today.
- **The way out stays open.** If iOS, web, or a stricter compile-time ban on JVM APIs is ever wanted, `photoframe-core` can become a KMP module with `commonMain`, once the app moves to AGP 8.10+. The core only uses `kotlin.*` and `IntArray`, so the move is mechanical.

## Open questions and uncertainties

- **Speed of pure-Kotlin Lanczos, unmeasured.**
  - A rough estimate for upscaling a crop to a ~6.5 megapixel window is ~12 taps × 4 channels per output pixel, about 300 million multiply-adds. That should be well under a second on a desktop JVM.
  - It could take several seconds on the 32-bit Android tablet. That matters only once framing moves into the app (out of scope for now).
  - A spike should time it on the 57 test photos.
- **Android memory.** A 3840x2400 `IntArray` is 36.9 MB on the Java heap, and a collage holds the output plus the border plus a crop at once. That may need `android:largeHeap`, or a later native-backed `Raster` on Android. This is not a concern for the CLI.
- **Lanczos-3 vs Catmull-Rom by eye.** Ringing on the high-contrast edges of the border window is a possible issue. Decide with a side-by-side spike, which costs little because both share the resampler.
- **EXIF on the JVM is unverified.** Writing a PNG `eXIf` through `UnknownChunks`, and copying a JPEG `APP1` segment, are inferred from the metadata docs and the `PNGImageWriter` source, not run. They are only relevant if EXIF output is ever required. The current inputs have none.
- **OpenCV interaction with [#4](https://github.com/ryanpudd/WeddingPhotobooth/issues/4).** If detection picks OpenCV, decode could use `imread`, giving one decoder on both platforms. The pixel pipeline above still stands. We have not checked whether the Java bindings expose `imwriteWithMetadata`.
- **Skiko on Android is unverified.** The README claims Android support, but we found no Maven Central artifact. Either way it excludes `armeabi-v7a`, so we stopped there.
- **JPEG output differs between platforms.** The JPEG encoders differ (libjpeg in the JDK, Skia/libjpeg-turbo on Android). Byte-for-byte equal JPEGs across platforms are not achievable. Tests should compare `Raster`s before encoding, or decoded PNGs.

## Sources

[imageio-pkg]: https://docs.oracle.com/en/java/javase/21/docs/api/java.desktop/javax/imageio/package-summary.html
[rendering-hints]: https://docs.oracle.com/en/java/javase/21/docs/api/java.desktop/java/awt/RenderingHints.html
[alpha-composite]: https://docs.oracle.com/en/java/javase/21/docs/api/java.desktop/java/awt/AlphaComposite.html
[jdk-8204188]: https://bugs.openjdk.org/browse/JDK-8204188
[jpeg-metadata]: https://docs.oracle.com/en/java/javase/21/docs/api/java.desktop/javax/imageio/metadata/doc-files/jpeg_metadata.html
[png-metadata]: https://docs.oracle.com/en/java/javase/21/docs/api/java.desktop/javax/imageio/metadata/doc-files/png_metadata.html
[png-writer-src]: https://github.com/openjdk/jdk/blob/master/src/java.desktop/share/classes/com/sun/imageio/plugins/png/PNGImageWriter.java
[tm-readme]: https://github.com/haraldk/TwelveMonkeys/blob/master/README.md
[tm-maven]: https://repo1.maven.org/maven2/com/twelvemonkeys/imageio/imageio-jpeg/maven-metadata.xml
[tm-resample]: https://github.com/haraldk/TwelveMonkeys/blob/master/common/common-image/src/main/java/com/twelvemonkeys/image/ResampleOp.java
[skiko-readme]: https://github.com/JetBrains/skiko/blob/master/README.md
[skiko-maven]: https://search.maven.org/solrsearch/select?q=g:org.jetbrains.skiko&rows=60&wt=json
[skiko-sampling]: https://github.com/JetBrains/skiko/blob/master/skiko/src/commonMain/kotlin/org/jetbrains/skia/SamplingMode.kt
[skiko-encoded]: https://github.com/JetBrains/skiko/blob/master/skiko/src/commonMain/kotlin/org/jetbrains/skia/EncodedImageFormat.kt
[opencv-pom]: https://repo1.maven.org/maven2/org/opencv/opencv/5.0.0/opencv-5.0.0.pom
[openpnp-maven]: https://repo1.maven.org/maven2/org/openpnp/opencv/maven-metadata.xml
[opencv-imgproc]: https://github.com/opencv/opencv/blob/4.x/modules/imgproc/include/opencv2/imgproc.hpp
[opencv-imgcodecs]: https://github.com/opencv/opencv/blob/4.12.0/modules/imgcodecs/include/opencv2/imgcodecs.hpp
[android-bitmap]: https://developer.android.com/reference/android/graphics/Bitmap
[android-paint]: https://developer.android.com/reference/android/graphics/Paint
[androidx-exif]: https://developer.android.com/reference/androidx/exifinterface/media/ExifInterface
[androidx-exif-releases]: https://developer.android.com/jetpack/androidx/releases/exifinterface
[android-bitmap-memory]: https://developer.android.com/topic/performance/graphics/manage-memory
[android-library]: https://developer.android.com/studio/projects/android-library
[android-jdks]: https://developer.android.com/build/jdks
[android-kmp-plugin]: https://developer.android.com/kotlin/multiplatform/plugin
[kotlin-expect-actual]: https://kotlinlang.org/docs/multiplatform/multiplatform-expect-actual.html
[lint-invalidpackage]: https://googlesamples.github.io/android-custom-lint-rules/checks/InvalidPackage.md.html

- Java SE 21 API: [javax.imageio package summary][imageio-pkg], [RenderingHints][rendering-hints], [AlphaComposite][alpha-composite], [JPEG metadata format][jpeg-metadata], [PNG metadata format][png-metadata]
- OpenJDK: [JDK-8204188][jdk-8204188] (JPEG writer drops alpha support in JDK 11), [PNGImageWriter.java][png-writer-src]
- TwelveMonkeys: [README][tm-readme], [Maven Central metadata][tm-maven], [ResampleOp.java][tm-resample]
- Skiko: [README][skiko-readme], [Maven Central search][skiko-maven], [SamplingMode.kt][skiko-sampling], [EncodedImageFormat.kt][skiko-encoded]
- OpenCV: [org.opencv:opencv 5.0.0 POM][opencv-pom], [org.openpnp:opencv metadata][openpnp-maven], [imgproc.hpp][opencv-imgproc], [imgcodecs.hpp 4.12.0][opencv-imgcodecs], `org.bytedeco:opencv-platform` [metadata](https://repo1.maven.org/maven2/org/bytedeco/opencv-platform/maven-metadata.xml)
- Android: [Bitmap][android-bitmap], [Paint][android-paint], [androidx ExifInterface][androidx-exif], [ExifInterface releases][androidx-exif-releases], [Managing bitmap memory][android-bitmap-memory], [Create an Android library][android-library], [Java versions in Android builds][android-jdks], [Android KMP library plugin][android-kmp-plugin], [lint InvalidPackage][lint-invalidpackage]
- Kotlin: [expect and actual declarations][kotlin-expect-actual]
- Repo: `build.gradle.kts`, `app/build.gradle.kts`, `app/src/main/java/com/ryanpudd/photobooth/MainActivity.kt`. Test photos inspected with a PNG chunk lister.
