# How do we detect people plus the props they hold?

Research for [#5](https://github.com/ryanpudd/WeddingPhotobooth/issues/5), part of the `photoframe` map ([#3](https://github.com/ryanpudd/WeddingPhotobooth/issues/3)). Terms follow [GLOSSARY.md](../../GLOSSARY.md): photo, border, window, crop, interesting content, framed photo.

This note covers the detection **approach** and the **models**. Which runtime and library run them from Kotlin on the JVM and Android is [#4](https://github.com/ryanpudd/WeddingPhotobooth/issues/4). Runtime points appear here only where they change the choice of approach.

## Recommendation

Use **person detection plus pose keypoints**. Add an optional union with COCO objects, limited to objects held at the hands. Leave open-vocabulary detection out for now. Keep it as a documented upgrade path. Don't use saliency.

1. **Person boxes** come from a permissively licensed COCO-80 detector. Examples: YOLOX-s or -m (Apache-2.0), or the Apache-2.0 sizes of RT-DETR, D-FINE or RF-DETR.
2. **Hand boxes** come from a top-down pose model. For each wrist, place a box beyond the wrist, along the elbow-to-wrist line, sized from the forearm length. When a hand is raised above the shoulder, extend the hand box upward as well. RTMPose (Apache-2.0 code, ONNX models via rtmlib) is a good fit. The licence of its training data is an open point (see below).
3. **Held objects** are a cheap extra, because the person detector already reports all 80 COCO classes. Union an object only if its class is on a "holdable" allowlist (book, cell phone, cup, bottle, wine glass, teddy bear, umbrella, sports ball, frisbee, kite, scissors, remote, handbag, and so on) **and** it overlaps a hand box. Never union an object just because it touches a person box. In our living-room photos that would pull in sofas and pot plants on almost every shot.
4. **Interesting content** is the union of each primary subject's person box, hand boxes and held-object boxes, plus a small margin. Secondary people (small, in the background, or a fragment at the photo edge) are optional boxes. Neither crop should be forced to include them nor allowed to cut through their faces.

Open-vocabulary detection (OWLv2, Apache-2.0) is the upgrade if real wedding props get clipped: signs on sticks, giant glasses, hats, boas. Those props aren't in the test set. On our photos OWLv2 was noisy and slow: about 5 s a photo on CPU, and a 163 to 614 MB model. YOLO-World (GPL-3.0) and YOLOE (AGPL-3.0) are ruled out by licence.

## What the test photos contain

I looked at all 57 photos in `tmp/WeddingPhotobooth/` (1920×1080) as downscaled contact sheets, and at several of them at full size.

- **Office shots (2026-08-03, 2026-08-04, 18 photos):** a seated man close to the camera. Two have two thumbs up that reach past his shoulders and toward the top of the photo (`2026-08-03-19-35-22.png`, `-35-33`). Four (two sessions) have a second adult standing behind or beside him. There's a bright ceiling light, and certificates and a shelf of ornaments on the wall.
- **Living-room shots (2026-09-12 11:48 to 12:02, 35 photos, plus 4 later ones):** mostly two kids dancing, often full-body and small (roughly a quarter to a third of the photo height). Arms and legs are flung out, there are high kicks, and in some shots one child stands right next to the camera, so only a torso or an arm shows at the photo edge. A child sits on the sofa in the background in a few early shots. A sofa, pot plants, a TV and ornaments appear in every shot.
- **The only clear held prop** is a girl in a princess dress holding an open book (`2026-09-12-12-02-00.png`, `-02-26`). A child on the sofa holds a tablet (`2026-09-13-07-00-49.png`), and that child is motion-blurred.
- **Close-ups and odd cases:** a couple's selfie with an arm outstretched (`2026-09-12-12-00-11.png`), a face shot from below (`2026-09-12-22-07-54.png`), an upside-down couple (`2026-09-12-12-00-39.png`), a headless torso with a child behind (`2026-09-12-11-48-51.png`), a unicorn T-shirt torso cut by the left edge (`2026-09-13-07-00-49.png`), and a motion-blurred child at the left edge (`2026-09-13-07-01-09.png`).

So in this set, held objects are rare and small. The common risks are limbs and hands outside the person box, fragments of people at the edges, background people, and blur. Real wedding props such as signs, giant glasses, hats and frames on sticks are **not represented**. Collecting a few test photos with them is the main gap for tuning (see open questions).

## Throwaway experiment

I ran a small Python experiment in a temp directory outside the repo. Nothing from it is committed. It used ONNX Runtime 1.31 on CPU with 4 intra-op threads, on this Windows dev machine, over all 57 photos:

- **YOLOX-s**, the official COCO-80 ONNX (35.9 MB, Apache-2.0) from the [YOLOX v0.1.1rc0 release](https://github.com/Megvii-BaseDetection/YOLOX/releases/tag/0.1.1rc0), at 640×640 with a confidence of 0.3.
- **RTMPose-m (body7)** with its YOLOX-m Human-Art person detector, both through [rtmlib](https://github.com/Tau-J/rtmlib) (Apache-2.0) in `balanced` mode. COCO-17 keypoints.
- **OWLv2 base patch16 ensemble**, the int8 ONNX from [`onnx-community/owlv2-base-patch16-ensemble-ONNX`](https://huggingface.co/onnx-community/owlv2-base-patch16-ensemble-ONNX) (163 MB), on 13 representative photos. Prompts were "a photo of a {person, sign, balloon, book, hat, glasses, prop, phone, tablet, toy, hand}" with a threshold of 0.15.
- **OpenCV saliency** (spectral residual and fine-grained) on 4 photos.

| Model | Mean time per photo (CPU, incl. Python pre/post) | Whole set |
|---|---|---|
| YOLOX-s, COCO-80 | 0.34 s | about 19 s for 57 |
| YOLOX-m person detector + RTMPose-m | 0.39 s | about 22 s for 57 |
| OWLv2 base, int8 | 4.9 s | about 4.7 min for 57 (projected) |

These timings are indicative only: Python, one machine, no tuning. They show that the recommended pair finishes the whole set in under a minute, and OWLv2 takes several minutes.

### Results by approach

**Person detection (YOLOX-s, COCO):**

- It found every main subject, including the upside-down couple (0.80, though one merged box spanning both people, plus a second box at 0.58).
- The face-from-below close-up scored only 0.58, so a person threshold near 0.5 is needed, not 0.7.
- It also returned background people: the child on the sofa (0.75 to 0.83), a fragment at the right edge (0.32), and edge-cut torsos (0.82 to 0.96, `[0,2,257,1069]`-style boxes).
- It missed the motion-blurred child in `2026-09-13-07-01-09.png`. The Human-Art YOLOX-m inside rtmlib did find a box there.

**Pose keypoints (RTMPose-m):**

- I built hand boxes as a square of side equal to the forearm length, centred half a forearm beyond the wrist. Wrist and elbow scores had to be at least 0.3.
- In 31 of 87 people matched to a YOLOX-s person box, the hand box reached more than 30 px outside the person box.
- The clearest case is the thumbs-up photo. The YOLOX-s box `[-10,257,1357,1075]` stops near the right thumb and below the left thumb's tip.
- The largest overshoots (300 to 440 px) are in overlapping two-child shots. Some of them are pose-to-box matching artefacts, so the heuristic needs a sanity cap. It must never grow a box past the photo, nor by more than about one forearm.

**COCO objects:** across the 57 photos, YOLOX-s returned these non-person classes at a confidence of at least 0.3:

| Count | Classes |
|---|---|
| 58 | couch |
| 34 | potted plant |
| 14 | chair |
| 12 | book |
| 7 | bowl |
| 7 | laptop |
| 6 | remote |
| 1 each | cell phone, skateboard, frisbee, tv, teddy bear, cat, horse |

None of these is a held prop except possibly one "book". It **missed** the princess's book at 0.3. The office "books" are certificates and shelf items. The "horse" and "cat" are the unicorn print on a T-shirt. A naive "union with anything touching a person" would grow interesting content to most of the room in nearly every living-room photo. That is why the union must be limited to holdable classes near a hand.

**OWLv2:**

- "glasses" was reliable (0.36 to 0.57 on real spectacles), and "hand" found the raised thumb (0.52).
- It boxed the princess's book, but as "a tablet" (0.28) and "a prop" (0.18), not as "a book".
- It also produced plenty of false positives near the threshold. "Balloon" fired on the ceiling light (0.20). "Phone" and "tablet" fired on random rectangles: the TV, picture frames, cushions.
- Its person scores were low (0.15 to 0.33), so OWLv2 needs per-prompt thresholds and can't be the person detector.
- The generic prompt "prop" is not meaningful.

**Saliency (OpenCV):** the Otsu-thresholded saliency box covered almost the whole photo in every case tried. In the living room it fires on the window, the rug, the sofa edges and wall art as strongly as on the children. In the office, the fine-grained map traces every edge. As a box source it adds nothing.

## Approach 1: person detector plus COCO objects

COCO has 80 object categories ([Lin et al. 2014, arXiv:1405.0312](https://arxiv.org/abs/1405.0312); list as used by [torchvision `_meta.py`](https://github.com/pytorch/vision/blob/main/torchvision/models/_meta.py)). For photobooth props:

| Typical photobooth prop | In COCO? |
|---|---|
| Phone held up | Yes: `cell phone` |
| Drink: glass, bottle, cup | Yes: `wine glass`, `bottle`, `cup` |
| Book, card, menu, order of service | Partly: `book` (cards and loose paper have no class) |
| Cuddly toy | Yes: `teddy bear` |
| Umbrella, parasol | Yes: `umbrella` |
| Handbag or clutch | Yes: `handbag` |
| Tie, bow tie | Yes: `tie` (worn, so it sits inside the person box anyway) |
| Ball, frisbee, kite, racket, bat | Yes |
| Flowers or bouquet | No (only `potted plant` and `vase`) |
| Sign, placard, speech bubble, chalkboard, "Mr & Mrs" letters | **No** (the only sign class is `stop sign`) |
| Balloons | **No** |
| Giant glasses or sunglasses | **No** |
| Hats, crowns, tiaras, wigs | **No** |
| Moustache or lips on a stick, masks | **No** |
| Feather boa, scarf | **No** |
| Hand-held empty picture-frame prop (the physical object) | **No** (probably detected as `tv` or `laptop`, unverified) |

COCO covers everyday held objects but **none of the classic photobooth-prop categories**. On its own it can't guarantee that a sign or giant glasses stay in the crop.

Permissively licensed COCO-80 detectors with ONNX paths:

| Model | Licence (code / weights) | Notes |
|---|---|---|
| YOLOX-nano/tiny/s/m/l/x ([repo](https://github.com/Megvii-BaseDetection/YOLOX), [paper arXiv:2107.08430](https://arxiv.org/abs/2107.08430)) | Apache-2.0 | Official ONNX files in the GitHub release. Used in the experiment. |
| RT-DETR ([repo](https://github.com/lyuwenyu/RT-DETR)) | Apache-2.0 | NMS-free DETR. ONNX export in the repo. |
| D-FINE ([repo](https://github.com/Peterande/D-FINE)) | Apache-2.0 | ONNX export script. The README warns its **Objects365** checkpoints "may be subject to the Objects365 dataset terms and should not be assumed to be commercially cleared". Use only COCO checkpoints. |
| RF-DETR ([repo](https://github.com/roboflow/rf-detr)) | Nano/Small/Medium/Large: Apache-2.0. **Atto, Femto, Pico, XL, 2XL: PML 1.0** (Roboflow Platform Model License; commercial self-hosting needs an Enterprise add-on) | The README doesn't confirm ONNX export. |
| SSD MobileNetV2, EfficientDet-Lite0/2 ([MediaPipe Object Detector](https://developers.google.com/edge/mediapipe/solutions/vision/object_detector)) | MediaPipe is Apache-2.0. The page gives no model-specific licence. | TFLite, 256 to 448 px input. Lite0 int8 runs in 29 ms on a Pixel 6 CPU. Lower accuracy on small subjects. |
| Ultralytics YOLOv5/v8/11 and later | **AGPL-3.0** ([repo licence](https://github.com/ultralytics/ultralytics)) | Avoid. |

## Approach 2: person detector plus pose keypoints

Keypoints say where the hands are. Anything a guest holds up sits at or just beyond a wrist, whatever the object is. That makes it the only approach here that covers **unrecognised** props without an open vocabulary. It also fixes the most common real failure in our set: thumbs, hands and outstretched arms just outside the person box.

| Model | Licence | Keypoints | Multi-person | Notes |
|---|---|---|---|---|
| RTMPose-s/m ([mmpose project](https://github.com/open-mmlab/mmpose/tree/main/projects/rtmpose), [arXiv:2303.07399](https://arxiv.org/abs/2303.07399)), via [rtmlib](https://github.com/Tau-J/rtmlib) | Apache-2.0 code | COCO-17 (wrists, elbows, shoulders). WholeBody variants add hands. | Top-down, so it needs a person detector | On an i7-11700 with ONNX Runtime: RTMPose-m pose only 11 ms, RTMPose-s 4.5 ms. Body7 RTMPose-m scores 74.9 AP on COCO. ONNX models ready to use. |
| RTMO-s/m/l ([mmpose project](https://github.com/open-mmlab/mmpose/tree/main/projects/rtmo), [arXiv:2312.07526](https://arxiv.org/abs/2312.07526)) | Apache-2.0 code | COCO-17 | One-stage: boxes plus keypoints in a single model | ONNX packages exist only for the body7-trained weights. V100 latency 8.9 to 19.1 ms. |
| MediaPipe Pose Landmarker ([docs](https://developers.google.com/edge/mediapipe/solutions/vision/pose_landmarker), [model card](https://storage.googleapis.com/mediapipe-assets/Model%20Card%20BlazePose%20GHUM%203D.pdf)) | Apache-2.0 (MediaPipe) | 33, including wrist, index finger, pinky and **thumb** tips | `num_poses` sets the maximum and **defaults to 1** | Android, iOS, Python and Web. **No desktop Java**, which matters for #4. Detector input is 224 px, landmarker 256 px. |
| MoveNet Lightning/Thunder/MultiPose | Apache-2.0 (per the [Xenova ONNX mirror](https://huggingface.co/Xenova/movenet-multipose-lightning); the original Google card was not reachable) | COCO-17 | MultiPose variant | Aimed at "live fitness, health, and wellness" ([TF tutorial](https://www.tensorflow.org/hub/tutorials/movenet)). The single-pose models track one main subject. |
| RF-DETR Keypoint (Preview) | Apache-2.0 | COCO-17 | One-stage | 71.8 AP. It is a preview. |
| Ultralytics YOLO-pose | **AGPL-3.0** | COCO-17 | One-stage | Avoid. |

Notes:

- **Training data of body7 weights.** The body7 models are trained on AI Challenger, COCO, CrowdPose, MPII, sub-JHMDB, Halpe and PoseTrack18 ([RTMPose README](https://github.com/open-mmlab/mmpose/tree/main/projects/rtmpose)). I found no weight licence beyond the repository's Apache-2.0, and I didn't check each dataset's terms. If that matters, mmpose also publishes COCO-only RTMPose checkpoints, which would need converting to ONNX.
- **What a pose model can't see.** Hands that leave the photo, hands hidden behind a prop, and props held well away from the body, for example a sign on a long stick. The hand-box margin covers a prop of about hand-to-forearm size. A large sign held up needs either a generous upward allowance when the wrist is above the shoulder, or approach 3.

## Approach 3: open-vocabulary detectors

| Model | Licence | Size | CPU fit for about 60 photos at 1920×1080 | ONNX / TFLite |
|---|---|---|---|---|
| OWLv2 base patch16 ([card](https://huggingface.co/google/owlv2-base-patch16-ensemble), [arXiv:2306.09683](https://arxiv.org/abs/2306.09683), code in [scenic](https://github.com/google-research/scenic)) | Apache-2.0 | 155M parameters. Input 960×960 ([Qualcomm AI Hub card](https://huggingface.co/qualcomm/OWL-V2)). | **Measured 4.9 s a photo** (int8, CPU), so about 5 minutes per batch. Workable for a batch CLI, slow on a phone: the vision encoder alone takes 0.4 to 1.5 s on Snapdragon NPUs. | Community ONNX: fp32 614 MB, fp16 307 MB, int8 163 MB, q4f16 128 MB. TFLite only via third parties. |
| OmDet-Turbo ([card](https://huggingface.co/omlab/omdet-turbo-swin-tiny-hf), [arXiv:2403.06892](https://arxiv.org/abs/2403.06892)) | Apache-2.0 | About 0.1B parameters | Claims 100.2 FPS with TensorRT and a language cache (the abstract doesn't name the hardware). No CPU numbers. | The card mentions no ONNX. |
| Grounding DINO ([repo](https://github.com/IDEA-Research/GroundingDINO), [tiny card](https://huggingface.co/IDEA-Research/grounding-dino-tiny)) | Apache-2.0 | Tiny: 172M parameters (Swin-T) | Has a CPU-only mode. Heavy, with no published speed. | The official repo mentions no ONNX. Uses custom deformable-attention ops. |
| YOLO-World ([repo](https://github.com/AILab-CVC/YOLO-World), [arXiv:2401.17270](https://arxiv.org/abs/2401.17270)) | **GPL-3.0**. Commercial licence by request. The Ultralytics port is **AGPL-3.0**. | S/M/L/X | 52 FPS on a V100 (L). A fixed vocabulary can be reparameterised into the weights. | ONNX and TFLite (including INT8) export documented. |
| YOLOE ([repo](https://github.com/THU-MIG/yoloe)) | **AGPL-3.0** | | | |

Against our photos, OWLv2 found the right things for specific nouns ("glasses", "hand") and boxed the book only under the wrong noun. It hallucinated "balloon" and "tablet" near the threshold, and its person scores were too low to rank people. It would need its own score thresholds for each prompt, and the prompt list must name real props, not "prop".

The permissive models are large. YOLO-World is the one built for speed and export, and it's GPL. Together that makes open vocabulary a poor default. It's a reasonable **opt-in** if later photos show signs or giant glasses being clipped. If it is ever used, run it only for hand regions that have a raised wrist, not across the whole photo, and union only boxes that overlap a hand box.

## Approach 4: saliency

- **Classic saliency** (OpenCV spectral residual, fine-grained) failed on our photos (see the experiment): bright windows, ceiling lights and cluttered shelves outscore the people.
- **Learned salient-object models** such as U²-Net (Apache-2.0; `u2net` 176.3 MB, `u2netp` 4.7 MB; [repo](https://github.com/xuebinqin/U-2-Net)) are trained to segment one dominant object. I didn't test them here. In a room with two dancing children and a sofa, "the salient object" is not a well-defined target.
- **The only role saliency could fill is ruled out.** It could act as a fallback when no people are found, but #3 puts people-free photos out of scope and keeps the whole photo.

Recommendation: don't use saliency.

## Failure modes, and how the recommended combination handles them

| Failure mode | Seen in | Handling |
|---|---|---|
| Hands, thumbs or arms outside the person box | `2026-08-03-19-35-22`, many dancing shots (31 of 87 people) | Keypoint hand boxes. Cap the growth at the photo bounds and about one forearm. |
| Partial people at the photo edge (torso, arm or leg only) | `2026-09-12-11-55-38`, `-11-55-55`, `-11-56-16`, `2026-09-13-07-00-49`, `-07-01-09`, `2026-09-12-12-02-26` (foot) | A person box that touches the photo edge and has no face keypoints (nose and eyes below threshold) is a **fragment**. Make it optional, not required, unless it is the only person. |
| Background people | Child on the sofa in `2026-09-12-11-48-51`, `-11-49-34`, `-11-49-55`, `-11-50-13`. Small figure in `2026-09-13-06-40-20`. | Mark people as **secondary** when their box area is under about 25% of the largest person's and they don't overlap a primary person. Secondary people are optional. Tune the threshold on the test set. |
| Motion blur | `2026-09-13-07-01-09` (left child), `2026-09-13-07-00-49` (sofa child) | Detectors still fire on most blurred people, but YOLOX-s missed one that the Human-Art YOLOX-m caught. Prefer the larger detector, or union both detectors' person boxes if cheap. Keypoints on blurred limbs are low-confidence, so the score threshold drops them. |
| Upside-down photos | `2026-09-12-12-00-39` | It was still detected (0.80), but as one merged box, and pose is weaker. #3 lists "detect or leave alone" as open. A cheap check: if rotating the photo 180° raises the mean person and keypoint score, the photo is probably inverted. |
| Unusual viewpoint (face from below, very close) | `2026-09-12-22-07-54` (person 0.58) | Use a person threshold of about 0.5. If one person fills most of the photo, the zoom rule falls back to 1× anyway. |
| COCO false positives | "horse" and "cat" on a T-shirt print, "book" on certificates, `tv` or `laptop` on picture frames | Use only holdable classes, and only near a hand. A false positive inside a person box is harmless. |
| Large props held away from the body | Not in the test set | Upward allowance for raised wrists. Otherwise needs approach 3. |

## How the boxes feed the crop rules

What detection hands to the crop step: a list of **subjects**. Each subject has:

- `personBox` and a score.
- `extentBox`: the union of `personBox`, its hand boxes and any held-object boxes, clipped to the photo.
- `role`, one of `primary`, `secondary` (small or background) or `fragment` (edge-cut, no face).
- `faceBox`, optional: from the nose, eye and ear keypoints. It is used so a crop never cuts through a face.

The crop rule, as already settled in #3: window aspect ratio, zoom from about 1×, 1.4× and 2×, never past 2×.

1. **No subjects:** keep the whole photo (out of scope per #3). Use the widest crop with the window's aspect ratio, centred.
2. **Required box** R is the union of the `extentBox` of every `primary` subject, plus a margin of about 5% of R's size. That stops hands and heads touching the window edge.
3. **Optional boxes** O are the `secondary` and `fragment` subjects. They are never required. A candidate crop that cuts through an optional subject's `faceBox` is penalised, so the crop either takes in the whole face or leaves it out.
4. **For each zoom level, highest first** (2×, 1.4×, 1×): the crop size is fixed by the zoom and the window's aspect ratio. A zoom is **feasible** if a crop of that size can contain R. Place it centred on R, then shift it to stay inside the photo. Among the placements that contain R, prefer the one with the fewest cut optional faces.
5. **Pick the highest feasible zoom.** If even 1× can't contain R (R is wider or taller than the largest crop at the window's aspect ratio), use 1×. Centre it on R, with priority to keeping primary `faceBox`es whole. Flag the photo in the contact sheet for review or override.
6. **Overrides** from the crop override file replace steps 2 to 5 for that photo.

This keeps detection and cropping separate. The crop logic only sees rectangles with roles. It can be unit-tested with hand-written boxes, and the detector can be swapped (#4) without touching it.

## Open questions and uncertainties

- **No real wedding props in the test set.** Signs, giant glasses, hats and boas are untested. Before the spec is final, take about 10 booth photos with typical props held up, high and to the side. Those photos decide whether the hand-box allowance is enough or OWLv2 is needed.
- **Licence of the pose weights' training data.** The body7 RTMPose weights include datasets whose terms I didn't check. COCO-only checkpoints are the fallback.
- **The same model on JVM and Android.** RTMPose and YOLOX as ONNX work wherever ONNX Runtime works. MediaPipe Pose has no desktop Java. That is #4's call.
- **Thresholds are first guesses.** Person score about 0.5, keypoint score 0.3, hand-box size of one forearm, secondary-person area about 25%, margin about 5%. Tune all of them on the 57 photos with the contact sheet.
- **Timings come from Python on one dev machine.** JVM ONNX Runtime should be similar, but #4 should measure it.
- **The upside-down handling** depends on #3's open item.

## Sources

Primary sources consulted on 2026-11-01:

- COCO: Lin et al., *Microsoft COCO: Common Objects in Context*, [arXiv:1405.0312](https://arxiv.org/abs/1405.0312). Category list in [torchvision `_meta.py`](https://github.com/pytorch/vision/blob/main/torchvision/models/_meta.py).
- YOLOX: [repo](https://github.com/Megvii-BaseDetection/YOLOX) (Apache-2.0), [release 0.1.1rc0](https://github.com/Megvii-BaseDetection/YOLOX/releases/tag/0.1.1rc0), [arXiv:2107.08430](https://arxiv.org/abs/2107.08430).
- RT-DETR: [repo](https://github.com/lyuwenyu/RT-DETR) (Apache-2.0). D-FINE: [repo](https://github.com/Peterande/D-FINE) (Apache-2.0, with the Objects365 caveat). RF-DETR: [repo](https://github.com/roboflow/rf-detr) (Apache-2.0 or PML 1.0, by size).
- Ultralytics: [repo](https://github.com/ultralytics/ultralytics) (AGPL-3.0).
- MediaPipe: [Object Detector](https://developers.google.com/edge/mediapipe/solutions/vision/object_detector), [Pose Landmarker](https://developers.google.com/edge/mediapipe/solutions/vision/pose_landmarker), [BlazePose GHUM model card](https://storage.googleapis.com/mediapipe-assets/Model%20Card%20BlazePose%20GHUM%203D.pdf), [repo](https://github.com/google-ai-edge/mediapipe) (Apache-2.0).
- RTMPose and RTMO: [mmpose RTMPose](https://github.com/open-mmlab/mmpose/tree/main/projects/rtmpose), [mmpose RTMO](https://github.com/open-mmlab/mmpose/tree/main/projects/rtmo), [mmpose](https://github.com/open-mmlab/mmpose) (Apache-2.0), [rtmlib](https://github.com/Tau-J/rtmlib) (Apache-2.0), [arXiv:2303.07399](https://arxiv.org/abs/2303.07399), [arXiv:2312.07526](https://arxiv.org/abs/2312.07526).
- MoveNet: [TensorFlow tutorial](https://www.tensorflow.org/hub/tutorials/movenet), [Xenova ONNX mirror](https://huggingface.co/Xenova/movenet-multipose-lightning).
- OWLv2: [model card](https://huggingface.co/google/owlv2-base-patch16-ensemble), [arXiv:2306.09683](https://arxiv.org/abs/2306.09683), [ONNX conversions](https://huggingface.co/onnx-community/owlv2-base-patch16-ensemble-ONNX), [Qualcomm AI Hub card](https://huggingface.co/qualcomm/OWL-V2).
- OmDet-Turbo: [model card](https://huggingface.co/omlab/omdet-turbo-swin-tiny-hf), [arXiv:2403.06892](https://arxiv.org/abs/2403.06892).
- Grounding DINO: [repo](https://github.com/IDEA-Research/GroundingDINO) (Apache-2.0), [tiny model card](https://huggingface.co/IDEA-Research/grounding-dino-tiny).
- YOLO-World: [repo](https://github.com/AILab-CVC/YOLO-World) (GPL-3.0), [arXiv:2401.17270](https://arxiv.org/abs/2401.17270). YOLOE: [repo](https://github.com/THU-MIG/yoloe) (AGPL-3.0).
- U²-Net: [repo](https://github.com/xuebinqin/U-2-Net) (Apache-2.0).
- Repository licences read from the GitHub API (`licenseInfo.spdxId`) on 2026-11-01.
