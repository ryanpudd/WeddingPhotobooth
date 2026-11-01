# Detection runtime and model for `photoframe`

Research for [#4](https://github.com/ryanpudd/WeddingPhotobooth/issues/4), part of the map [#3](https://github.com/ryanpudd/WeddingPhotobooth/issues/3). Checked on 2026-11-01.

**Question:** which on-device detection runtime and model should `photoframe` use? It runs from Kotlin on the JVM (Windows) now and should later run inside the Android app.

## Recommendation

**Runtime: ONNX Runtime's Java API.** Use `com.microsoft.onnxruntime:onnxruntime` on the JVM and `com.microsoft.onnxruntime:onnxruntime-android` in the app. **Model: RF-DETR Nano (Apache-2.0)**, exported to ONNX. The same `.onnx` file works on both platforms, and both use the same `ai.onnxruntime` Java API, so the detection code can sit in the plain Kotlin core module. Only the Gradle coordinate changes per platform.

- It is the only candidate with an official, maintained, Maven-published runtime for **both** desktop JVM on Windows and Android, **including the tablet's 32-bit `armeabi-v7a` ABI**.
- RF-DETR Nano found at least one person in all 57 test photos and caught partly cropped people at the frame edge. It took about 390 ms per photo on one CPU thread (see [Measurements](#measurements-on-the-test-photos)).
- Licences: MIT runtime, Apache-2.0 weights. Nothing copyleft or non-commercial.
- **Must do:** turn off ONNX Runtime telemetry. It is on by default in official builds and uploads to Microsoft (see [Licences and telemetry](#licences-and-telemetry)).

**Model runner-up: D-FINE-S (Apache-2.0)** on the same runtime. It is a smaller file (40 MB fp32) with similar accuracy and the same CPU time on one thread. It missed one edge-of-frame person that RF-DETR found. **RT-DETRv2-S (Apache-2.0)** is a solid third choice, about 1.5–2× slower.

**Runtime runner-up: OpenCV DNN, using JavaCV on the JVM and the official `org.opencv:opencv` AAR on Android.** It is Apache-2.0 and well maintained, but the desktop and Android packaging come from different publishers. The current JavaCV no longer ships 32-bit Android ARM, and its ONNX support for the newer transformer detectors is less proven than ONNX Runtime's. LiteRT and MediaPipe would be the natural Android choices, but neither has a desktop Java/Kotlin API, so they fail the "JVM on Windows now" requirement.

## Constraint that shapes everything: the tablet is 32-bit ARM

The app builds for `armeabi-v7a` only, with `minSdk 27` (`app/build.gradle.kts`). `photobooth-app-technical-brief.md` §2 says the device "*is* 32-bit ARM (`armeabi-v7a`) only". Any Android runtime must ship an `armeabi-v7a` native library and support API 27. I checked this by listing the `jni/` folder inside each AAR (table below).

## Runtime comparison

Versions and dates come from `maven-metadata.xml` on Maven Central / Google Maven and from GitHub releases, read on 2026-11-01. Sizes come from HTTP `Content-Length` or from listing the archive entries.

| | ONNX Runtime (Java) | LiteRT / TFLite | MediaPipe Tasks (vision) | OpenCV DNN | DJL |
|---|---|---|---|---|---|
| Latest | 1.31.1 (2026-10-26) | `com.google.ai.edge.litert:litert` 2.3.2 (2026-10-26) | `com.google.mediapipe:tasks-vision` 1.2.0 (2026-10-28) | OpenCV 5.0.0 (2026-06-06) / 4.14.0 (2026-07-19); JavaCV 1.5.14 (2026-08) | 0.40.0 on Maven (2026-10-30); GitHub release v0.39.0 (2026-10-20) |
| **Desktop JVM on Windows** | **Yes.** One jar, 48.6 MB, with natives for win-x64 (16.5 MB `onnxruntime.dll`), linux-x64/aarch64 and osx-aarch64. Needs the VC++ 2019 runtime. | **No.** Desktop is C++ and Python only. Kotlin/Java are Android only. | **No.** Platforms are Android, iOS, Python and Web. | **Not from OpenCV on Maven.** Use JavaCV `org.bytedeco:opencv` (Windows x64 natives jar 33.4 MB, plus OpenBLAS), the community `org.openpnp:opencv` (stale: 4.9.0, 2023-12), or the jar and dll in OpenCV's Windows installer. | Yes, as a wrapper. The ORT engine pulls in `onnxruntime` **1.21.1** (pinned, now 10 minor releases behind) plus gson, JNA, commons-compress and slf4j. |
| **Android** | Yes. `onnxruntime-android` AAR, 52.6 MB, minSdk 24. | Yes. AAR 7.3 MB, minSdk 24. | Yes. `tasks-core` natives, minSdk 24. | Yes. Official `org.opencv:opencv` 5.0.0 AAR, 145.9 MB, minSdk 21. | Yes. `ai.djl.android:onnxruntime` 0.40.0 (wraps ORT-android 1.21.1). |
| `armeabi-v7a` in the AAR | **Yes.** `libonnxruntime.so` 23.1 MB | Yes. `libLiteRt.so` 3.5 MB | Yes. `libmediapipe_tasks_jni.so` 10.5 MB | Official AAR: yes, `libopencv_java5.so` 21.5 MB. **JavaCV: no.** Only `android-arm64`/`x86_64` since 1.5.11. | Yes, through ORT |
| Same model file on both | **Yes** (`.onnx`) | n/a (no desktop) | n/a (no desktop) | Yes (`.onnx`), if both builds can import it | Yes (`.onnx`) |
| Runtime licence | MIT | Apache-2.0 | Apache-2.0 | OpenCV: Apache-2.0. JavaCV: Apache-2.0 or GPLv2+ with Classpath exception, at your choice. | Apache-2.0 |
| Kotlin/Java samples | Java API docs and tests. Official Kotlin Android sample `mobile/examples/object_detection/android` (but its model is YOLOv8n, which is AGPL; do not copy it) | Kotlin Android samples | Kotlin Android samples (`mediapipe-samples/examples/object_detection/android`) | Java API mirrors C++. Few detection samples. | Many Java examples, model zoo |
| Maintenance | Very active (monthly releases) | Very active | Very active (1.0 → 1.2 in Oct 2026) | Active; 4.x and 5.x both maintained | Active, but the ORT pin lags and `ai.djl.android:core` is stuck at 0.33.0 (2025-06) |

Sources:
- ORT: Maven Central [`onnxruntime`](https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime/) and [`onnxruntime-android`](https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/) (jar/AAR contents listed; minSdk read from the AAR's `AndroidManifest.xml`); [Java get-started](https://onnxruntime.ai/docs/get-started/with-java.html) ("Java 8 or newer"; the page still says "macOS x64", but the 1.31.1 jar actually ships osx-aarch64); [install requirements](https://onnxruntime.ai/docs/install/) ("Windows builds require Visual C++ 2019 runtime"); [releases](https://github.com/microsoft/onnxruntime/releases).
- LiteRT: [Google Maven group index](https://dl.google.com/android/maven2/com/google/ai/edge/litert/group-index.xml); [LiteRT overview](https://developers.google.com/edge/litert/overview) (desktop: "C++ API", "Python API"; Kotlin/Java only under Android); [README](https://github.com/google-ai-edge/LiteRT) (Apache-2.0). `org.tensorflow:tensorflow-lite` stopped at 2.17.0 (2025-01) and has moved to LiteRT.
- MediaPipe: [Object Detector guide](https://developers.google.com/edge/mediapipe/solutions/vision/object_detector) (platform guides: Android, iOS, Python, Web); `tasks-vision`/`tasks-core` 1.2.0 POMs and AAR manifests on Google Maven; [releases](https://github.com/google-ai-edge/mediapipe/releases).
- OpenCV: [`org.opencv:opencv` 5.0.0](https://repo1.maven.org/maven2/org/opencv/opencv/5.0.0/) (an Android AAR); [5.0.0 release assets](https://github.com/opencv/opencv/releases/tag/5.0.0); [4→5 migration guide, DNN section](https://github.com/opencv/opencv/wiki/OpenCV-4-to-5-migration) (new default DNN engine with fallback to classic; Darknet and Caffe parsers removed; optional `ENGINE_ORT`); [JavaCV `org.bytedeco:opencv` classifiers](https://repo1.maven.org/maven2/org/bytedeco/opencv/4.14.0-1.5.14/); [JavaCV LICENSE.txt](https://github.com/bytedeco/javacv/blob/master/LICENSE.txt).
- DJL: POMs for [`ai.djl.onnxruntime:onnxruntime-engine:0.40.0`](https://repo1.maven.org/maven2/ai/djl/onnxruntime/onnxruntime-engine/0.40.0/) and [`ai.djl.android:onnxruntime:0.40.0`](https://repo1.maven.org/maven2/ai/djl/android/onnxruntime/0.40.0/) (both depend on ORT 1.21.1); [`ai.djl:api:0.40.0` POM](https://repo1.maven.org/maven2/ai/djl/api/0.40.0/). `ai.djl.tflite:tflite-engine` stopped at 0.27.0 (2024-03).

### Notes per runtime

- **ONNX Runtime.** Plain `OrtEnvironment` / `OrtSession` / `OnnxTensor`, the same classes on the JVM and Android. The full Android AAR is big (23 MB `.so` for v7a). ORT's documented "reduced operator" custom builds can shrink it later if APK size matters. That is out of scope while app integration is out of scope.
- **LiteRT / MediaPipe.** These are excellent on Android. MediaPipe's Object Detector gives you the post-processing and an Apache-2.0 EfficientDet-Lite0 that ran in 29–61 ms on a Pixel 6 CPU. But you would need a second runtime for the Windows CLI, which defeats "one core module". You could only use them in the CLI by running Python or C++ as a side process.
- **OpenCV DNN.** Attractive if `photoframe` uses OpenCV for image work anyway. But you must use two different packagings, JavaCV on desktop and the official AAR on Android, and they share no versioning. JavaCV's prebuilt Android artifacts no longer cover `armeabi-v7a`. I did **not** test whether OpenCV 5's new engine loads the RF-DETR or D-FINE ONNX graphs. Deformable-attention DETRs use ops such as `GridSample` that OpenCV DNN has historically lagged on.
- **DJL.** It adds a model zoo and pre/post-processing helpers on top of ORT, but pins an old ORT and adds transitive dependencies. Its value is mostly for engines we don't need. Calling ORT directly is simpler, and the post-processing for a DETR model is about 20 lines (sigmoid over logits, then scale the normalised `cx, cy, w, h` boxes).

## Model comparison

**Interesting content** is people plus the props they hold. Every model below is a COCO-80 detector with a `person` class. None has classes for photobooth props (signs, hats, glasses, frames), so props have to come from crop rules (for example, padding person boxes upward and outward) or from an open-vocabulary model. That is out of scope here.

| Model | Weights licence | COCO AP (val) | Input | ONNX file (fp32 / int8) | Notes |
|---|---|---|---|---|---|
| **RF-DETR Nano** | **Apache-2.0** | 48.4 | 384×384 | 103 MB / 27.5 MB ([onnx-community/rfdetr_nano-ONNX](https://huggingface.co/onnx-community/rfdetr_nano-ONNX)) | Roboflow; DINOv2 backbone; 30.5 M params. Small, Medium and Large are Apache-2.0 too. **Atto/Femto/Pico/XL/2XL are PML 1.0 (not free for self-hosted commercial use). Avoid them.** |
| **D-FINE-S** (Objects365→COCO) | **Apache-2.0** | 50.7 (48.5 COCO-only) | 640×640 | 40 MB / 10.7 MB ([onnx-community/dfine_s_obj2coco-ONNX](https://huggingface.co/onnx-community/dfine_s_obj2coco-ONNX)) | 10 M params. The upstream repo is Apache-2.0. |
| D-FINE-N | Apache-2.0 | 42.8 | 640×640 | 14.6 MB / 4.3 MB | 4 M params. **The onnx-community export missed people in 46/57 test photos.** Do not use without investigating. |
| **RT-DETRv2-S** (r18vd) | **Apache-2.0** | 48.1 | 640×640 | 77 MB / 20 MB ([onnx-community/rtdetr_v2_r18vd-ONNX](https://huggingface.co/onnx-community/rtdetr_v2_r18vd-ONNX)) | 20 M params; Baidu's original. Use these weights, **not** the `rtdetr-*.pt` files served through the AGPL-3.0 `ultralytics` package. |
| YOLOX-S / Tiny / Nano | Apache-2.0 | 40.5 / 32.8 / 25.8 | 640 / 416 / 416 | 34 / 19 / 3 MB ONNX in [GitHub release 0.1.1rc0](https://github.com/Megvii-BaseDetection/YOLOX/releases/tag/0.1.1rc0) | CNN; needs NMS. Last release 2022-04 and the repo is quiet. Fine as a tiny fallback. |
| EfficientDet-Lite0/2, SSD MobileNetV2 | Apache-2.0 (TF Model Garden / MediaPipe) | lower | 320 / 448 / 256 | `.tflite` | The MediaPipe Object Detector models. TFLite only, so they don't suit the JVM. |
| RTMDet / RTMPose (OpenMMLab) | Apache-2.0 | — | — | ONNX through MMDeploy | An alternative, including pose. |
| **Ultralytics YOLOv5/v8/11/26 (and models served through the `ultralytics` package)** | **AGPL-3.0** | — | — | — | **Avoid.** It would force the closed-source app to open its source, or you'd need a paid licence. |
| **YOLOv10, YOLOE (THU-MIG)** | **AGPL-3.0** | — | — | — | **Avoid.** |
| **YOLO-World (AILab-CVC)** | **GPL-3.0** | — | — | — | **Avoid.** (Open-vocabulary detector.) |
| **YOLOv6 (Meituan), YOLOv7, YOLOv9 (WongKinYiu)** | **GPL-3.0** | — | — | — | **Avoid** in a closed-source app. |
| RF-DETR Atto/Femto/Pico/XL/2XL | **Roboflow PML 1.0** | — | — | — | **Avoid.** Commercial self-hosting needs a paid add-on. |

Pose and keypoints: these are not needed for box-based crops. Permissive options if wanted later: **RF-DETR Keypoint (preview, Apache-2.0)**, **RTMPose/RTMO (Apache-2.0)**, and **MediaPipe Pose Landmarker** (BlazePose GHUM; TFLite, so Android/Python/Web only; `num_poses` defaults to 1). Its model card is linked from the guide, but I could not read the PDF to confirm the weights licence. **Ultralytics YOLO-pose is AGPL-3.0.**

Sources: the licences come from each repo's GitHub licence field: [RF-DETR](https://github.com/roboflow/rf-detr) (README "License" section and model table), [D-FINE](https://github.com/Peterande/D-FINE) (README model table), [RT-DETR](https://github.com/lyuwenyu/RT-DETR) (`rtdetrv2_pytorch/README.md` table), [YOLOX](https://github.com/Megvii-BaseDetection/YOLOX), [ultralytics](https://github.com/ultralytics/ultralytics) (AGPL-3.0), [THU-MIG/yolov10](https://github.com/THU-MIG/yolov10) (AGPL-3.0), [meituan/YOLOv6](https://github.com/meituan/YOLOv6) and [WongKinYiu/yolov9](https://github.com/WongKinYiu/yolov9) (GPL-3.0), [mmdetection](https://github.com/open-mmlab/mmdetection) and [mmpose](https://github.com/open-mmlab/mmpose) (Apache-2.0), [YOLO-World](https://github.com/AILab-CVC/YOLO-World) (GPL-3.0), [THU-MIG/yoloe](https://github.com/THU-MIG/yoloe) (AGPL-3.0). The HF model cards ([PekingU/rtdetr_v2_r18vd](https://huggingface.co/PekingU/rtdetr_v2_r18vd), [ustc-community/dfine_n_coco](https://huggingface.co/ustc-community/dfine_n_coco), [Roboflow/rf-detr-nano](https://huggingface.co/Roboflow/rf-detr-nano)) all say `apache-2.0`. The MediaPipe models and latencies come from the [Object Detector guide](https://developers.google.com/edge/mediapipe/solutions/vision/object_detector) and the [Pose Landmarker guide](https://developers.google.com/edge/mediapipe/solutions/vision/pose_landmarker).

## Measurements on the test photos

I ran each model on the 57 photos in `tmp/WeddingPhotobooth/` (1920×1080), using **onnxruntime 1.31.1 (Python, CPU EP)** on an **Intel Core Ultra 7 155H**. Each photo was stretched to the model's square input, with person score > 0.5. The JVM binding calls the same native library, so expect similar timings from Kotlin. The scratch script was not committed.

| Model | Median ms / photo, 4 threads | Median ms / photo, 1 thread | Photos with 0 people found |
|---|---|---|---|
| RF-DETR Nano (fp32, 384) | 180 | 390 | **0 / 57** |
| D-FINE-S obj2coco (fp32, 640) | 144 | 389 | **0 / 57** |
| RT-DETRv2-S (fp32, 640) | 258 | — | **0 / 57** |
| D-FINE-N (fp32, 640) | 54 | — | **46 / 57** (broken export or preprocessing) |

- RF-DETR Nano, D-FINE-S and RT-DETRv2-S agree on the people count for most photos. Spot checks of the annotated previews showed tight boxes on whole and partly visible people. On `2026-09-12-11-55-55.png`, RF-DETR also boxed a person cut off at the left edge, which D-FINE-S missed.
- The RF-DETR ONNX needs **ImageNet mean/std normalisation**, even though its `preprocessor_config.json` says `do_normalize: false`. Its labels are COCO ids, 1-based (`person` = 1). The RT-DETR and D-FINE exports take rescale-only input (`/255`) with `person` = 0. Inputs are `pixel_values [N,3,H,W]` and outputs are `logits` and `pred_boxes` (normalised cx, cy, w, h). No NMS is needed.
- **Speed on the tablet is unmeasured.** A 32-bit ARM tablet will be much slower than this laptop: expect seconds per photo, not milliseconds. That is fine for batch framing but should be measured before any in-app use. The int8 exports (about a quarter of the size) are the first thing to try there.

## Licences and telemetry

- **Runtime licences:** ONNX Runtime is MIT. LiteRT, MediaPipe, OpenCV and DJL are Apache-2.0. JavaCV is Apache-2.0 or GPLv2+ with Classpath exception, at your choice. None of these block a closed-source app.
- **Weights:** the recommended RF-DETR Nano, the runner-up D-FINE-S and RT-DETRv2-S are all **Apache-2.0**. **All Ultralytics YOLO weights (v5/v8/11/26, including pose) and THU-MIG YOLOv10/YOLOE are AGPL-3.0. YOLOv6/v7/v9 and YOLO-World are GPL-3.0. Five RF-DETR sizes are under Roboflow's PML 1.0.** Keep all of these out.
- **ONNX Runtime telemetry is ON by default** in official builds. Since 1.31 the Windows, Linux, macOS and Android builds use the 1DS SDK, which sends trace events to Microsoft over HTTPS ([`docs/Privacy.md` at v1.31.1](https://github.com/microsoft/onnxruntime/blob/v1.31.1/docs/Privacy.md); [PR #32384](https://github.com/microsoft/onnxruntime/pull/32384)). Custom model metadata is not uploaded, and nothing indicates image data is sent. The 1.31.1 Android AAR's manifest adds `INTERNET` and `ACCESS_NETWORK_STATE` permissions and an `ai.onnxruntime.TelemetryInitializer` content provider. To disable it:
  - set `ORT_DISABLE_TELEMETRY=1` before ORT initialises (this blocks the uploader and device ID for the whole process);
  - also call `OrtEnvironment.setTelemetry(false)`, but the docs warn that a minimal initialisation event may already have been sent before this call;
  - on Android, setting the variable is harder, so remove the provider with manifest merger (`tools:node="remove"`), and consider pinning ORT ≤ 1.30 or building with `--no_telemetry`. Verify this when the app integration happens.

## Open uncertainties

1. **Tablet speed and memory** for RF-DETR Nano or D-FINE-S on `armeabi-v7a` haven't been measured. They matter only once the core moves into the app.
2. **Props.** None of these detectors has prop classes. Whether person boxes plus padding cover held-up props, or whether an open-vocabulary detector is needed (OWLv2, from google-research/scenic, and IDEA-Research/GroundingDINO are Apache-2.0 but heavy; YOLO-World is GPL-3.0 and YOLOE is AGPL-3.0), is a crop-rules question. It needs photos with props: the test set seemed to have few, but I didn't check systematically.
3. **Pretraining data terms.** RF-DETR and the D-FINE obj2coco weights are pretrained on Objects365, and RF-DETR also uses DINOv2. The weights themselves are Apache-2.0. I did not check whether Objects365's own dataset terms affect the weights. If that matters, the COCO-only D-FINE-S or RT-DETRv2-S weights avoid the question.
4. **ORT telemetry on Android** is new in 1.31. The exact behaviour of the provider and the variable inside an app wasn't tested.
5. **D-FINE-N's failure** was not investigated. It could be the export, or preprocessing I got wrong.
6. **OpenCV 5 DNN** loading of these DETR graphs was not tested.
7. The **MediaPipe BlazePose model-card licence** was not confirmed, because the PDF could not be read.
