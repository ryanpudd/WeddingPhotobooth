# Booth Resilience & Battery Alerting Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When the USB webcam disconnects (because the battery pack died), the booth shows a full-screen instruction and posts one `@everyone` Discord alert, with an automatic all-clear on reconnect — plus the diagnostics and demo mode needed to prove it works before the wedding.

**Architecture:** The trigger is the *existing* `USBMonitor.OnDeviceConnectListener` callbacks in `MainActivity` — no new detection mechanism. A pure, time-injected state machine (`CameraAlertState`) turns raw connect/disconnect events into four effects (show screen, hide screen, send alert, send all-clear); `MainActivity` executes those effects. The state machine is ticked from the *existing* 5-second heartbeat runnable, so no new thread is introduced. All Discord work is plain `HttpURLConnection` on a single-thread executor. Every piece of logic worth testing is a pure Kotlin object with no Android imports.

**Tech Stack:** Kotlin, Android (minSdk/targetSdk 27, compileSdk 33), saki4510t UVCCamera AARs, AWS Android SDK (S3), androidx.security EncryptedSharedPreferences, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-09-20-booth-resilience-spec.md`

## Global Constraints

- `minSdk 27`, `targetSdk 27`, `compileSdk 33`, `abiFilters` = `armeabi-v7a` only.
- **No new Gradle dependencies.** Use `java.net.HttpURLConnection` and hand-rolled JSON.
- **Unit tests are plain JVM JUnit 4** in `app/src/test/java/com/ryanpudd/photobooth/`. There is no Robolectric and `returnDefaultValues` is not enabled. **Test code and the code under test must not touch Android framework classes at runtime** — in particular do not use `org.json`, which is a stub that throws in unit tests.
- Build requires the UVCCamera AARs at `C:/workspace/repos/github.com/saki4510t/UVCCamera` (set via `uvccRoot` in `settings.gradle.kts`).
- Test command: `./gradlew :app:testDebugUnitTest`. Build command: `./gradlew :app:assembleDebug`.
- Follow existing patterns: `object` singletons for stateless helpers, `EncryptedSharedPreferences` via a store object for secrets, background work off the UI thread.
- Webcam is a Logitech BRIO: `vendor-id 1133`, `product-id 2142`, device class `239/2/1`, video interface class `14`.
- On-screen fault copy is exactly: `Booth needs a battery swap, find someone to help`
- Alert debounce is 30 seconds. One alert only, never repeated. All-clear on reconnect.

## Note on task ordering

The confirmed sequence put the diagnostic button before Discord alerting. The diagnostic button *posts to Discord*, so the sender has to exist first. Tasks 2 and 3 therefore build the Discord plumbing, task 4 builds the diagnostic button on top of it, and task 6 wires up the alerting. Nothing else moved.

---

### Task 1: USB permission persistence

Android binds a runtime USB permission grant to the device node path (`/dev/bus/usb/001/016`), and every replug mints a new node — which is why the prompt returns forever. A `device_filter.xml` plus an attach intent-filter surfaces the "use by default for this USB device" checkbox, which is keyed to the device instead.

**Files:**
- Create: `app/src/main/res/xml/device_filter.xml`
- Modify: `app/src/main/AndroidManifest.xml` (the `.MainActivity` `<activity>` block)

**Interfaces:**
- Consumes: nothing
- Produces: nothing in code. Behavioural precondition for all later manual testing: the camera reconnects without a permission prompt.

- [ ] **Step 1: Create the device filter**

`app/src/main/res/xml/device_filter.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- Logitech BRIO, exact match (vendor 0x046D, product 0x085E) -->
    <usb-device vendor-id="1133" product-id="2142" />
    <!-- Any USB composite device using an Interface Association Descriptor -->
    <usb-device class="239" subclass="2" protocol="1" />
    <!-- Any UVC video device, so a swapped webcam still matches -->
    <usb-device class="14" />
</resources>
```

- [ ] **Step 2: Register the attach filter on MainActivity**

In `app/src/main/AndroidManifest.xml`, inside the existing `<activity android:name=".MainActivity">` element, after the existing `<intent-filter>` for MAIN/LAUNCHER, add:

```xml
            <intent-filter>
                <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />
            </intent-filter>
            <meta-data
                android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"
                android:resource="@xml/device_filter" />
```

- [ ] **Step 3: Build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Verify on the device (manual — no unit test is possible for a manifest change)**

1. Uninstall the app completely: `adb uninstall com.ryanpudd.photobooth.debug`
2. Install the new build: `./gradlew :app:installDebug`
3. Unplug the webcam, then plug it back in.
4. **Expected:** a dialog appears offering to open Photobooth, *with a checkbox reading "Use by default for this USB device"*. Tick it and confirm.
5. Unplug and replug **three more times**.
6. **Expected:** no further prompts; the camera preview returns on its own each time.
7. Confirm no "which app?" chooser appears (the other installed booth app, `com.slrbooth.studio`, should not match — its filters cover classes 6 and 7 and specific vendor IDs, none of which is the BRIO).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/xml/device_filter.xml app/src/main/AndroidManifest.xml
git commit -m "fix: persist USB permission across webcam replugs

Android binds runtime USB grants to the device node path, which changes on
every replug. A device_filter plus attach intent-filter surfaces the
'use by default' checkbox, which is keyed to the device instead.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01AGz3hsSLyn8QH3SV5R73V3"
```

---

### Task 2: Discord payload building (pure)

Hand-rolled JSON, because `org.json` is an Android stub that throws in unit tests. This task is pure logic and fully tested; the network call arrives in Task 3.

**Files:**
- Create: `app/src/main/java/com/ryanpudd/photobooth/DiscordPayload.kt`
- Test: `app/src/test/java/com/ryanpudd/photobooth/DiscordPayloadTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `DiscordPayload.isValidWebhookUrl(url: String): Boolean`
  - `DiscordPayload.escapeJson(raw: String): String`
  - `DiscordPayload.build(content: String, mentionEveryone: Boolean): String`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/ryanpudd/photobooth/DiscordPayloadTest.kt`:

```kotlin
package com.ryanpudd.photobooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscordPayloadTest {

    @Test
    fun isValidWebhookUrl_acceptsDiscordHosts() {
        assertTrue(DiscordPayload.isValidWebhookUrl("https://discord.com/api/webhooks/123/abc"))
        assertTrue(DiscordPayload.isValidWebhookUrl("https://discordapp.com/api/webhooks/123/abc"))
    }

    @Test
    fun isValidWebhookUrl_rejectsAnythingElse() {
        assertFalse(DiscordPayload.isValidWebhookUrl(""))
        assertFalse(DiscordPayload.isValidWebhookUrl("http://discord.com/api/webhooks/123/abc"))
        assertFalse(DiscordPayload.isValidWebhookUrl("https://example.com/api/webhooks/123/abc"))
        assertFalse(DiscordPayload.isValidWebhookUrl("   "))
    }

    @Test
    fun escapeJson_escapesQuotesBackslashesAndNewlines() {
        assertEquals("""a\"b""", DiscordPayload.escapeJson("a\"b"))
        assertEquals("""a\\b""", DiscordPayload.escapeJson("a\\b"))
        assertEquals("""a\nb""", DiscordPayload.escapeJson("a\nb"))
        assertEquals("""a\tb""", DiscordPayload.escapeJson("a\tb"))
    }

    @Test
    fun escapeJson_escapesOtherControlCharacters() {
        assertEquals("""a\u0000b""", DiscordPayload.escapeJson("a\u0000b"))
    }

    @Test
    fun escapeJson_leavesPlainTextAlone() {
        assertEquals("Booth needs a battery swap", DiscordPayload.escapeJson("Booth needs a battery swap"))
    }

    @Test
    fun build_withMention_requestsEveryoneParse() {
        val json = DiscordPayload.build("camera lost", mentionEveryone = true)
        assertEquals(
            """{"content":"camera lost","allowed_mentions":{"parse":["everyone"]}}""",
            json
        )
    }

    @Test
    fun build_withoutMention_suppressesAllMentions() {
        val json = DiscordPayload.build("all clear", mentionEveryone = false)
        assertEquals(
            """{"content":"all clear","allowed_mentions":{"parse":[]}}""",
            json
        )
    }

    @Test
    fun build_escapesContent() {
        val json = DiscordPayload.build("line1\nline2 \"quoted\"", mentionEveryone = false)
        assertEquals(
            """{"content":"line1\nline2 \"quoted\"","allowed_mentions":{"parse":[]}}""",
            json
        )
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*DiscordPayloadTest*"`
Expected: FAIL — compilation error, `Unresolved reference: DiscordPayload`

- [ ] **Step 3: Write the implementation**

`app/src/main/java/com/ryanpudd/photobooth/DiscordPayload.kt`:

```kotlin
package com.ryanpudd.photobooth

/**
 * Pure JSON construction for Discord webhook posts.
 *
 * Deliberately hand-rolled rather than using org.json: org.json ships as a
 * throwing stub in JVM unit tests, and keeping this file free of Android
 * imports is what makes it testable at all.
 */
object DiscordPayload {

    private const val WEBHOOK_PREFIX_CURRENT = "https://discord.com/api/webhooks/"
    private const val WEBHOOK_PREFIX_LEGACY = "https://discordapp.com/api/webhooks/"

    fun isValidWebhookUrl(url: String): Boolean {
        val trimmed = url.trim()
        return trimmed.startsWith(WEBHOOK_PREFIX_CURRENT) || trimmed.startsWith(WEBHOOK_PREFIX_LEGACY)
    }

    fun escapeJson(raw: String): String {
        val sb = StringBuilder(raw.length + 16)
        for (c in raw) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        return sb.toString()
    }

    /**
     * A webhook post only produces a phone push when it mentions someone, so
     * [mentionEveryone] is the difference between an alert and a silent log line.
     */
    fun build(content: String, mentionEveryone: Boolean): String {
        val parse = if (mentionEveryone) "\"everyone\"" else ""
        return """{"content":"${escapeJson(content)}","allowed_mentions":{"parse":[$parse]}}"""
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*DiscordPayloadTest*"`
Expected: PASS, 8 tests

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ryanpudd/photobooth/DiscordPayload.kt app/src/test/java/com/ryanpudd/photobooth/DiscordPayloadTest.kt
git commit -m "feat: add pure Discord webhook payload builder

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01AGz3hsSLyn8QH3SV5R73V3"
```

---

### Task 3: Discord sender and encrypted webhook storage

**Files:**
- Create: `app/src/main/java/com/ryanpudd/photobooth/DiscordNotifier.kt`
- Create: `app/src/main/java/com/ryanpudd/photobooth/AlertSettingsStore.kt`
- Modify: `app/src/main/res/layout/dialog_admin_settings.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/com/ryanpudd/photobooth/MainActivity.kt` (`showAdminSettingsDialog`, lines 451-495)

**Interfaces:**
- Consumes: `DiscordPayload.build`, `DiscordPayload.isValidWebhookUrl`
- Produces:
  - `class DiscordNotifier(webhookUrlProvider: () -> String?)` with `send(content: String, mentionEveryone: Boolean)`, `sendBlocking(url: String, content: String, mentionEveryone: Boolean): Int`, `shutdown()`
  - `AlertSettingsStore.saveWebhookUrl(context, url)`, `AlertSettingsStore.loadWebhookUrl(context): String?`, `AlertSettingsStore.hasWebhookUrl(context): Boolean`

- [ ] **Step 1: Write the sender**

`app/src/main/java/com/ryanpudd/photobooth/DiscordNotifier.kt`:

```kotlin
package com.ryanpudd.photobooth

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Fire-and-forget Discord webhook sender.
 *
 * No retries by design: a battery alert that lands 40 minutes late is noise,
 * and the full-screen message is the primary channel. Contains no Android
 * imports so it stays unit-testable and usable from any thread.
 */
class DiscordNotifier(private val webhookUrlProvider: () -> String?) {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "DiscordNotifier").apply { isDaemon = true }
    }

    /** Queues a send on the background executor. Never throws, never blocks the caller. */
    fun send(content: String, mentionEveryone: Boolean) {
        val url = webhookUrlProvider() ?: return
        if (!DiscordPayload.isValidWebhookUrl(url)) return
        executor.execute {
            runCatching { sendBlocking(url, content, mentionEveryone) }
        }
    }

    /** Returns the HTTP status code. Discord answers 204 on success. Must not run on the UI thread. */
    fun sendBlocking(url: String, content: String, mentionEveryone: Boolean): Int {
        val body = DiscordPayload.build(content, mentionEveryone).toByteArray(Charsets.UTF_8)
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "WeddingPhotobooth/1.0")
        }
        return try {
            connection.outputStream.use { it.write(body) }
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 10_000
    }
}
```

- [ ] **Step 2: Write the encrypted settings store**

`app/src/main/java/com/ryanpudd/photobooth/AlertSettingsStore.kt`:

```kotlin
package com.ryanpudd.photobooth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Encrypted on-device storage for the Discord webhook URL, entered via the
 * admin settings screen. Mirrors CredentialsStore: the webhook URL is a bearer
 * token, so it gets the same treatment as the AWS secret.
 */
object AlertSettingsStore {
    private const val PREFS_NAME = "alert_settings_prefs"
    private const val KEY_WEBHOOK_URL = "discord_webhook_url"

    private fun prefs(context: Context): SharedPreferences {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        return EncryptedSharedPreferences.create(
            PREFS_NAME,
            masterKeyAlias,
            context.applicationContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun saveWebhookUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_WEBHOOK_URL, url.trim()).apply()
    }

    /** The stored webhook URL, or null if absent or not a Discord webhook. */
    fun loadWebhookUrl(context: Context): String? {
        val stored = prefs(context).getString(KEY_WEBHOOK_URL, "") ?: ""
        return if (DiscordPayload.isValidWebhookUrl(stored)) stored.trim() else null
    }

    fun hasWebhookUrl(context: Context): Boolean = loadWebhookUrl(context) != null
}
```

- [ ] **Step 3: Add the strings**

In `app/src/main/res/values/strings.xml`, before the closing `</resources>`:

```xml
    <string name="label_webhook_url">Discord Webhook URL</string>
    <string name="hint_webhook_unchanged">(unchanged)</string>
    <string name="admin_webhook_invalid">Not a valid Discord webhook URL</string>
    <string name="btn_send_test_alert">Send test alert</string>
    <string name="btn_send_diagnostics">Send diagnostics</string>
    <string name="toast_test_alert_sent">Test alert queued</string>
    <string name="toast_no_webhook">No Discord webhook configured</string>
```

- [ ] **Step 4: Add the webhook field and test button to the admin dialog**

In `app/src/main/res/layout/dialog_admin_settings.xml`, inside the `LinearLayout`, after the `inputKeyPrefix` `EditText`:

```xml
        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="12dp"
            android:text="@string/label_webhook_url" />

        <EditText
            android:id="@+id/inputWebhookUrl"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:inputType="textUri"
            android:maxLines="1" />

        <Button
            android:id="@+id/btnSendTestAlert"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:text="@string/btn_send_test_alert" />
```

- [ ] **Step 5: Wire the field and button into the settings dialog**

In `MainActivity.kt`, add a field alongside the other private fields (near `private var currentState = BoothState.IDLE` at line 80):

```kotlin
    private val discordNotifier = DiscordNotifier { AlertSettingsStore.loadWebhookUrl(this) }
```

In `showAdminSettingsDialog()`, after the existing `val inputKeyPrefix = ...` line, add:

```kotlin
        val inputWebhookUrl = view.findViewById<EditText>(R.id.inputWebhookUrl)
        val btnSendTestAlert = view.findViewById<Button>(R.id.btnSendTestAlert)

        if (AlertSettingsStore.hasWebhookUrl(this)) {
            inputWebhookUrl.hint = getString(R.string.hint_webhook_unchanged)
        }

        btnSendTestAlert.setOnClickListener {
            if (AlertSettingsStore.hasWebhookUrl(this)) {
                discordNotifier.send(
                    "\u2705 **Photobooth test alert** \u2014 if you can see this, alerts are working.",
                    mentionEveryone = true
                )
                Toast.makeText(this, R.string.toast_test_alert_sent, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, R.string.toast_no_webhook, Toast.LENGTH_SHORT).show()
            }
        }
```

Inside the `setPositiveButton(R.string.save)` lambda, immediately before the existing `CredentialsStore.save(` call, add:

```kotlin
                val webhookInput = inputWebhookUrl.text.toString().trim()
                if (webhookInput.isNotBlank()) {
                    if (!DiscordPayload.isValidWebhookUrl(webhookInput)) {
                        Toast.makeText(this, R.string.admin_webhook_invalid, Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    AlertSettingsStore.saveWebhookUrl(this, webhookInput)
                }
```

In `onDestroy()`, after the existing `uploadWorker.stop()` line, add:

```kotlin
        discordNotifier.shutdown()
```

- [ ] **Step 6: Build and verify on the device (manual — network and EncryptedSharedPreferences cannot be unit tested here)**

Run: `./gradlew :app:installDebug`

1. Create a dedicated Discord channel and a webhook on it (Channel Settings → Integrations → Webhooks → New Webhook → Copy Webhook URL).
2. Long-press the booth screen, tap the gear, enter PIN `1234`.
3. Paste the webhook URL, tap **Save**.
4. Reopen admin, tap **Send test alert**.
5. **Expected:** `Test alert queued` toast, and within a few seconds an `@everyone` message appears in the channel and pushes to every phone in it.
6. Reopen admin and confirm the webhook field shows the `(unchanged)` hint rather than the URL.
7. Enter a non-Discord URL and tap Save. **Expected:** `Not a valid Discord webhook URL` and the dialog stays open.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/ryanpudd/photobooth/DiscordNotifier.kt app/src/main/java/com/ryanpudd/photobooth/AlertSettingsStore.kt app/src/main/res/layout/dialog_admin_settings.xml app/src/main/res/values/strings.xml app/src/main/java/com/ryanpudd/photobooth/MainActivity.kt
git commit -m "feat: add Discord webhook sender and encrypted webhook storage

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01AGz3hsSLyn8QH3SV5R73V3"
```

---

### Task 4: Diagnostics report and an always-reachable admin gear

The admin gear is currently gated on `currentState == BoothState.IDLE` (`MainActivity.kt:174-181`), so it is unreachable in exactly the wedged state where diagnostics are needed. This task removes that gate and adds the report.

**Files:**
- Create: `app/src/main/java/com/ryanpudd/photobooth/BoothDiagnostics.kt`
- Test: `app/src/test/java/com/ryanpudd/photobooth/BoothDiagnosticsTest.kt`
- Modify: `app/src/main/res/layout/dialog_admin_settings.xml`
- Modify: `app/src/main/java/com/ryanpudd/photobooth/MainActivity.kt` (long-press handler at lines 174-181; `showAdminSettingsDialog`)

**Interfaces:**
- Consumes: `DiscordNotifier.send`
- Produces:
  - `data class DiagnosticsSnapshot(...)` — exact fields in Step 3
  - `BoothDiagnostics.format(snapshot: DiagnosticsSnapshot): String`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/ryanpudd/photobooth/BoothDiagnosticsTest.kt`:

```kotlin
package com.ryanpudd.photobooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoothDiagnosticsTest {

    private fun snapshot(
        boothState: String = "IDLE",
        cameraOpened: Boolean = true,
        cameraPreviewing: Boolean = true,
        usbDeviceAttached: Boolean = true,
        usbPermissionGranted: Boolean = true,
        deviceName: String? = "/dev/bus/usb/001/016",
        vendorId: Int? = 1133,
        productId: Int? = 2142,
        alertPhase: String = "HEALTHY",
        batteryLevelPercent: Int = 87,
        batteryPluggedRaw: Int = 2,
        appUptimeMs: Long = 3_600_000L,
        lastCameraErrors: List<String> = emptyList()
    ) = DiagnosticsSnapshot(
        boothState = boothState,
        cameraOpened = cameraOpened,
        cameraPreviewing = cameraPreviewing,
        usbDeviceAttached = usbDeviceAttached,
        usbPermissionGranted = usbPermissionGranted,
        deviceName = deviceName,
        vendorId = vendorId,
        productId = productId,
        alertPhase = alertPhase,
        batteryLevelPercent = batteryLevelPercent,
        batteryPluggedRaw = batteryPluggedRaw,
        appUptimeMs = appUptimeMs,
        lastCameraErrors = lastCameraErrors
    )

    @Test
    fun format_includesEveryDiagnosticField() {
        val report = BoothDiagnostics.format(snapshot())
        listOf(
            "IDLE", "opened=true", "previewing=true", "attached=true",
            "permission=true", "/dev/bus/usb/001/016", "1133", "2142",
            "HEALTHY", "87%", "plugged=2"
        ).forEach { assertTrue("missing '$it' in:\n$report", report.contains(it)) }
    }

    @Test
    fun format_wrapsInACodeBlockSoDiscordKeepsTheLayout() {
        val report = BoothDiagnostics.format(snapshot())
        assertTrue(report.contains("```"))
    }

    @Test
    fun formatUptime_rendersHoursAndZeroPaddedMinutes() {
        assertEquals("1h05m", BoothDiagnostics.formatUptime(3_600_000L + 300_000L))
        assertEquals("0h00m", BoothDiagnostics.formatUptime(0L))
        assertEquals("12h30m", BoothDiagnostics.formatUptime((12 * 60 + 30) * 60_000L))
    }

    @Test
    fun format_handlesNoDeviceAttached() {
        val report = BoothDiagnostics.format(
            snapshot(usbDeviceAttached = false, deviceName = null, vendorId = null, productId = null)
        )
        assertTrue(report.contains("attached=false"))
        assertTrue(report.contains("none"))
    }

    @Test
    fun format_listsRecentCameraErrors() {
        val report = BoothDiagnostics.format(
            snapshot(lastCameraErrors = listOf("open failed", "timeout"))
        )
        assertTrue(report.contains("open failed"))
        assertTrue(report.contains("timeout"))
    }

    @Test
    fun format_saysNoneWhenThereAreNoErrors() {
        val report = BoothDiagnostics.format(snapshot(lastCameraErrors = emptyList()))
        assertTrue(report.contains("errors: none"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*BoothDiagnosticsTest*"`
Expected: FAIL — `Unresolved reference: DiagnosticsSnapshot`

- [ ] **Step 3: Write the implementation**

`app/src/main/java/com/ryanpudd/photobooth/BoothDiagnostics.kt`:

```kotlin
package com.ryanpudd.photobooth

/**
 * A point-in-time view of everything worth knowing when the booth misbehaves.
 * Collected by MainActivity (which needs Android APIs), formatted here (which
 * deliberately does not, so it can be unit tested).
 */
data class DiagnosticsSnapshot(
    val boothState: String,
    val cameraOpened: Boolean,
    val cameraPreviewing: Boolean,
    val usbDeviceAttached: Boolean,
    val usbPermissionGranted: Boolean,
    val deviceName: String?,
    val vendorId: Int?,
    val productId: Int?,
    val alertPhase: String,
    val batteryLevelPercent: Int,
    val batteryPluggedRaw: Int,
    val appUptimeMs: Long,
    val lastCameraErrors: List<String>
)

object BoothDiagnostics {

    fun format(snapshot: DiagnosticsSnapshot): String {
        val errors = if (snapshot.lastCameraErrors.isEmpty()) {
            "none"
        } else {
            snapshot.lastCameraErrors.joinToString(" | ")
        }
        val device = snapshot.deviceName ?: "none"
        val vid = snapshot.vendorId?.toString() ?: "none"
        val pid = snapshot.productId?.toString() ?: "none"

        return buildString {
            append("\uD83D\uDD0D **Photobooth diagnostics**\n")
            append("```\n")
            append("booth state : ${snapshot.boothState}\n")
            append("alert phase : ${snapshot.alertPhase}\n")
            append("camera      : opened=${snapshot.cameraOpened} previewing=${snapshot.cameraPreviewing}\n")
            append("usb         : attached=${snapshot.usbDeviceAttached} permission=${snapshot.usbPermissionGranted}\n")
            append("device      : $device\n")
            append("ids         : vendor=$vid product=$pid\n")
            append("battery     : ${snapshot.batteryLevelPercent}% plugged=${snapshot.batteryPluggedRaw}\n")
            append("uptime      : ${formatUptime(snapshot.appUptimeMs)}\n")
            append("errors: $errors\n")
            append("```")
        }
    }

    fun formatUptime(uptimeMs: Long): String {
        val totalMinutes = uptimeMs / 60_000L
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return "%dh%02dm".format(hours, minutes)
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*BoothDiagnosticsTest*"`
Expected: PASS, 6 tests

- [ ] **Step 5: Make the admin gear reachable from any state**

In `MainActivity.kt`, replace the long-press handler (currently lines 174-181):

```kotlin
        rootLayout.setOnLongClickListener {
            if (currentState == BoothState.IDLE) {
                btnAdminGear.visibility = View.VISIBLE
                mainHandler.removeCallbacks(gearHideRunnable)
                mainHandler.postDelayed(gearHideRunnable, 8000)
                true
            } else {
                false
            }
        }
```

with:

```kotlin
        // Deliberately NOT gated on BoothState.IDLE: a camera failure can park the
        // app in CAPTURING or REVIEW, and that is exactly when diagnostics are needed.
        rootLayout.setOnLongClickListener {
            btnAdminGear.visibility = View.VISIBLE
            mainHandler.removeCallbacks(gearHideRunnable)
            mainHandler.postDelayed(gearHideRunnable, 8000)
            true
        }
```

- [ ] **Step 6: Add the diagnostics button to the admin dialog**

In `app/src/main/res/layout/dialog_admin_settings.xml`, immediately after the `btnSendTestAlert` Button added in Task 3:

```xml
        <Button
            android:id="@+id/btnSendDiagnostics"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="8dp"
            android:text="@string/btn_send_diagnostics" />
```

- [ ] **Step 7: Collect and send the snapshot**

In `MainActivity.kt`, add these imports alongside the existing ones:

```kotlin
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import java.util.concurrent.ConcurrentLinkedQueue
```

Add fields near the other private fields:

```kotlin
    private val recentCameraErrors = ConcurrentLinkedQueue<String>()
    private val appStartedElapsedMs = SystemClock.elapsedRealtime()
```

Add these two methods to `MainActivity`:

```kotlin
    private fun collectDiagnostics(): DiagnosticsSnapshot {
        val device: UsbDevice? = runCatching { mUSBMonitor?.deviceList?.firstOrNull() }.getOrNull()
        val batteryIntent = runCatching {
            registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()

        return DiagnosticsSnapshot(
            boothState = currentState.name,
            cameraOpened = runCatching { mCameraHandler?.isOpened == true }.getOrDefault(false),
            cameraPreviewing = runCatching { mCameraHandler?.isPreviewing == true }.getOrDefault(false),
            usbDeviceAttached = device != null,
            usbPermissionGranted = device != null &&
                runCatching { mUSBMonitor?.hasPermission(device) == true }.getOrDefault(false),
            deviceName = device?.deviceName,
            vendorId = device?.vendorId,
            productId = device?.productId,
            alertPhase = cameraAlertState.phase.name,
            batteryLevelPercent = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1,
            batteryPluggedRaw = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1,
            appUptimeMs = SystemClock.elapsedRealtime() - appStartedElapsedMs,
            lastCameraErrors = recentCameraErrors.toList()
        )
    }

    private fun recordCameraError(message: String) {
        recentCameraErrors.add(message)
        while (recentCameraErrors.size > MAX_RECENT_CAMERA_ERRORS) {
            recentCameraErrors.poll()
        }
    }
```

Add a `companion object` at the end of the class (or extend the existing one):

```kotlin
    companion object {
        private const val MAX_RECENT_CAMERA_ERRORS = 5
    }
```

In `showAdminSettingsDialog()`, after the `btnSendTestAlert` wiring from Task 3:

```kotlin
        val btnSendDiagnostics = view.findViewById<Button>(R.id.btnSendDiagnostics)
        btnSendDiagnostics.setOnClickListener {
            val report = BoothDiagnostics.format(collectDiagnostics())
            discordNotifier.send(report, mentionEveryone = false)
            AlertDialog.Builder(this)
                .setTitle(R.string.btn_send_diagnostics)
                .setMessage(report)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
```

> **Dependency note:** `cameraAlertState` is introduced in Task 5. Implement Task 5 before this step, or temporarily use the literal `"UNWIRED"` for `alertPhase` and replace it in Task 6.

- [ ] **Step 8: Build and verify on the device**

Run: `./gradlew :app:installDebug`

1. Long-press the screen **during the photo countdown** (not at idle). **Expected:** the gear appears — this is the behaviour that was broken before.
2. Tap the gear, enter the PIN, tap **Send diagnostics**.
3. **Expected:** an on-screen dialog shows the report, and the same report (no `@everyone`) appears in Discord in a code block.
4. Unplug the webcam and repeat. **Expected:** `attached=false`, `device : none`.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/ryanpudd/photobooth/BoothDiagnostics.kt app/src/test/java/com/ryanpudd/photobooth/BoothDiagnosticsTest.kt app/src/main/res/layout/dialog_admin_settings.xml app/src/main/java/com/ryanpudd/photobooth/MainActivity.kt
git commit -m "feat: add diagnostics report reachable from any booth state

The admin gear was gated on BoothState.IDLE, making it unreachable in
exactly the wedged state where diagnostics are needed.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01AGz3hsSLyn8QH3SV5R73V3"
```

---

### Task 5: Camera alert state machine (pure)

Turns noisy USB callbacks into four unambiguous effects. Pure and time-injected, so the 30-second debounce and the "a wobble sends nothing" guarantee are both tested without touching a device.

**Files:**
- Create: `app/src/main/java/com/ryanpudd/photobooth/CameraAlertState.kt`
- Test: `app/src/test/java/com/ryanpudd/photobooth/CameraAlertStateTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `enum class AlertEffect { SHOW_FAULT_SCREEN, HIDE_FAULT_SCREEN, SEND_DISCONNECT_ALERT, SEND_ALL_CLEAR }`
  - `class CameraAlertState(alertDelayMs: Long = 30_000L)` with `phase: Phase`, `onCameraLost(nowMs): List<AlertEffect>`, `onCameraBack(nowMs): List<AlertEffect>`, `onTick(nowMs): List<AlertEffect>`
  - `enum class CameraAlertState.Phase { HEALTHY, PENDING, ALERTED }`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/ryanpudd/photobooth/CameraAlertStateTest.kt`:

```kotlin
package com.ryanpudd.photobooth

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraAlertStateTest {

    private val delay = 30_000L

    @Test
    fun cameraLost_showsFaultScreenImmediatelyButSendsNothing() {
        val state = CameraAlertState(delay)
        assertEquals(listOf(AlertEffect.SHOW_FAULT_SCREEN), state.onCameraLost(1_000L))
        assertEquals(CameraAlertState.Phase.PENDING, state.phase)
    }

    @Test
    fun repeatedLossEventsAreIdempotent() {
        // USBMonitor fires both onDisconnect and onDettach for a single unplug.
        val state = CameraAlertState(delay)
        state.onCameraLost(1_000L)
        assertEquals(emptyList<AlertEffect>(), state.onCameraLost(1_100L))
        assertEquals(emptyList<AlertEffect>(), state.onCameraLost(1_200L))
    }

    @Test
    fun tickBeforeDelayElapses_sendsNothing() {
        val state = CameraAlertState(delay)
        state.onCameraLost(1_000L)
        assertEquals(emptyList<AlertEffect>(), state.onTick(20_000L))
        assertEquals(CameraAlertState.Phase.PENDING, state.phase)
    }

    @Test
    fun tickAfterDelayElapses_sendsTheAlertExactlyOnce() {
        val state = CameraAlertState(delay)
        state.onCameraLost(1_000L)
        assertEquals(listOf(AlertEffect.SEND_DISCONNECT_ALERT), state.onTick(31_000L))
        assertEquals(CameraAlertState.Phase.ALERTED, state.phase)
        assertEquals(emptyList<AlertEffect>(), state.onTick(90_000L))
        assertEquals(emptyList<AlertEffect>(), state.onTick(600_000L))
    }

    @Test
    fun reconnectWithinDebounce_hidesScreenAndSendsNothing() {
        // A kicked cable that recovers in 3 seconds must never ping anyone's phone.
        val state = CameraAlertState(delay)
        state.onCameraLost(1_000L)
        assertEquals(listOf(AlertEffect.HIDE_FAULT_SCREEN), state.onCameraBack(4_000L))
        assertEquals(CameraAlertState.Phase.HEALTHY, state.phase)
    }

    @Test
    fun reconnectAfterAlert_hidesScreenAndSendsAllClear() {
        val state = CameraAlertState(delay)
        state.onCameraLost(1_000L)
        state.onTick(31_000L)
        assertEquals(
            listOf(AlertEffect.HIDE_FAULT_SCREEN, AlertEffect.SEND_ALL_CLEAR),
            state.onCameraBack(60_000L)
        )
        assertEquals(CameraAlertState.Phase.HEALTHY, state.phase)
    }

    @Test
    fun reconnectWhenAlreadyHealthy_doesNothing() {
        val state = CameraAlertState(delay)
        assertEquals(emptyList<AlertEffect>(), state.onCameraBack(1_000L))
        assertEquals(CameraAlertState.Phase.HEALTHY, state.phase)
    }

    @Test
    fun tickWhenHealthy_doesNothing() {
        val state = CameraAlertState(delay)
        assertEquals(emptyList<AlertEffect>(), state.onTick(500_000L))
    }

    @Test
    fun secondOutageAfterRecovery_alertsAgain() {
        val state = CameraAlertState(delay)
        state.onCameraLost(1_000L)
        state.onTick(31_000L)
        state.onCameraBack(60_000L)

        assertEquals(listOf(AlertEffect.SHOW_FAULT_SCREEN), state.onCameraLost(100_000L))
        assertEquals(listOf(AlertEffect.SEND_DISCONNECT_ALERT), state.onTick(131_000L))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*CameraAlertStateTest*"`
Expected: FAIL — `Unresolved reference: CameraAlertState`

- [ ] **Step 3: Write the implementation**

`app/src/main/java/com/ryanpudd/photobooth/CameraAlertState.kt`:

```kotlin
package com.ryanpudd.photobooth

/** What MainActivity should do in response to a camera event. */
enum class AlertEffect {
    SHOW_FAULT_SCREEN,
    HIDE_FAULT_SCREEN,
    SEND_DISCONNECT_ALERT,
    SEND_ALL_CLEAR
}

/**
 * Decides when a camera outage becomes an alert.
 *
 * The screen message appears the instant the camera is lost, because the booth
 * is unusable immediately. The Discord alert waits [alertDelayMs] so a knocked
 * cable that recovers in a few seconds never reaches anyone's phone.
 *
 * Pure and time-injected: callers pass the clock in. Not thread-safe; drive it
 * from the main thread only.
 */
class CameraAlertState(private val alertDelayMs: Long = DEFAULT_ALERT_DELAY_MS) {

    enum class Phase { HEALTHY, PENDING, ALERTED }

    var phase: Phase = Phase.HEALTHY
        private set

    private var lostAtMs: Long = 0L

    /**
     * USBMonitor fires both onDisconnect and onDettach for one unplug, so this
     * is idempotent: only the first loss while healthy produces effects.
     */
    fun onCameraLost(nowMs: Long): List<AlertEffect> {
        if (phase != Phase.HEALTHY) return emptyList()
        phase = Phase.PENDING
        lostAtMs = nowMs
        return listOf(AlertEffect.SHOW_FAULT_SCREEN)
    }

    fun onCameraBack(nowMs: Long): List<AlertEffect> {
        return when (phase) {
            Phase.HEALTHY -> emptyList()
            Phase.PENDING -> {
                phase = Phase.HEALTHY
                listOf(AlertEffect.HIDE_FAULT_SCREEN)
            }
            Phase.ALERTED -> {
                phase = Phase.HEALTHY
                listOf(AlertEffect.HIDE_FAULT_SCREEN, AlertEffect.SEND_ALL_CLEAR)
            }
        }
    }

    fun onTick(nowMs: Long): List<AlertEffect> {
        if (phase != Phase.PENDING) return emptyList()
        if (nowMs - lostAtMs < alertDelayMs) return emptyList()
        phase = Phase.ALERTED
        return listOf(AlertEffect.SEND_DISCONNECT_ALERT)
    }

    companion object {
        const val DEFAULT_ALERT_DELAY_MS = 30_000L
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*CameraAlertStateTest*"`
Expected: PASS, 9 tests

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ryanpudd/photobooth/CameraAlertState.kt app/src/test/java/com/ryanpudd/photobooth/CameraAlertStateTest.kt
git commit -m "feat: add camera alert state machine with 30s debounce

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01AGz3hsSLyn8QH3SV5R73V3"
```

---

### Task 6: Wire the alerting into MainActivity

Connects the state machine to the real USB callbacks, adds the full-screen fault message, and drives the tick from the *existing* 5-second heartbeat runnable rather than introducing a new thread.

**Files:**
- Create: `app/src/main/java/com/ryanpudd/photobooth/AlertMessages.kt`
- Test: `app/src/test/java/com/ryanpudd/photobooth/AlertMessagesTest.kt`
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/com/ryanpudd/photobooth/MainActivity.kt` (`heartbeatRunnable` lines 105-111; `mOnDeviceConnectListener` lines 641-672; `onCreate`)

**Interfaces:**
- Consumes: `CameraAlertState`, `AlertEffect`, `DiscordNotifier.send`
- Produces:
  - `AlertMessages.FAULT_SCREEN_TEXT: String`
  - `AlertMessages.disconnect(isDemoMode: Boolean): String`
  - `AlertMessages.allClear(isDemoMode: Boolean): String`
  - `MainActivity.cameraAlertState: CameraAlertState` (consumed by Task 4's `collectDiagnostics`)

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/ryanpudd/photobooth/AlertMessagesTest.kt`:

```kotlin
package com.ryanpudd.photobooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertMessagesTest {

    @Test
    fun faultScreenText_isTheExactAgreedCopy() {
        assertEquals("Booth needs a battery swap, find someone to help", AlertMessages.FAULT_SCREEN_TEXT)
    }

    @Test
    fun disconnect_mentionsEveryoneAndExplainsTheFix() {
        val message = AlertMessages.disconnect(isDemoMode = false)
        assertTrue(message.contains("@everyone"))
        assertTrue(message.contains("battery"))
        assertFalse(message.contains("[TEST]"))
    }

    @Test
    fun disconnect_inDemoMode_isPrefixedSoHelpersDoNotPanic() {
        val message = AlertMessages.disconnect(isDemoMode = true)
        assertTrue(message.startsWith("[TEST]"))
        assertTrue(message.contains("@everyone"))
    }

    @Test
    fun allClear_saysTheBoothIsBackAndDoesNotMentionEveryone() {
        val message = AlertMessages.allClear(isDemoMode = false)
        assertTrue(message.contains("back"))
        assertFalse(message.contains("@everyone"))
        assertFalse(message.contains("[TEST]"))
    }

    @Test
    fun allClear_inDemoMode_isPrefixed() {
        assertTrue(AlertMessages.allClear(isDemoMode = true).startsWith("[TEST]"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*AlertMessagesTest*"`
Expected: FAIL — `Unresolved reference: AlertMessages`

- [ ] **Step 3: Write the implementation**

`app/src/main/java/com/ryanpudd/photobooth/AlertMessages.kt`:

```kotlin
package com.ryanpudd.photobooth

/**
 * Every piece of user-facing alert copy, in one place and free of Android
 * imports so the wording is covered by tests.
 */
object AlertMessages {

    /** Shown full-screen the instant the camera is lost. Anyone can act on it. */
    const val FAULT_SCREEN_TEXT = "Booth needs a battery swap, find someone to help"

    private const val TEST_PREFIX = "[TEST] "

    fun disconnect(isDemoMode: Boolean): String {
        val body = "🔴 @everyone **Photobooth camera lost** — most likely the battery pack. " +
            "Swap in the spare and it should come back on its own."
        return if (isDemoMode) TEST_PREFIX + body else body
    }

    fun allClear(isDemoMode: Boolean): String {
        val body = "🟢 **Photobooth is back up** — camera reconnected, nothing more to do."
        return if (isDemoMode) TEST_PREFIX + body else body
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*AlertMessagesTest*"`
Expected: PASS, 5 tests

- [ ] **Step 5: Add the fault overlay to the layout**

In `app/src/main/res/layout/activity_main.xml`, add as the **last child** of the root layout so it draws on top of everything:

```xml
    <TextView
        android:id="@+id/faultOverlay"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:background="#CC000000"
        android:clickable="true"
        android:focusable="true"
        android:gravity="center"
        android:padding="48dp"
        android:text="@string/fault_battery_swap"
        android:textColor="#FFFFFF"
        android:textSize="44sp"
        android:textStyle="bold"
        android:visibility="gone" />
```

> `clickable="true"` is deliberate: it swallows taps so nobody starts a countdown against a dead camera.

- [ ] **Step 6: Add the fault string**

In `app/src/main/res/values/strings.xml`, before `</resources>`:

```xml
    <string name="fault_battery_swap">Booth needs a battery swap, find someone to help</string>
```

- [ ] **Step 7: Wire the state machine into MainActivity**

Add a field near the other private fields:

```kotlin
    private val cameraAlertState = CameraAlertState()
    private lateinit var faultOverlay: TextView
```

In `onCreate()`, alongside the other `findViewById` calls (near `statusOverlayText = findViewById(R.id.statusOverlayText)`):

```kotlin
        faultOverlay = findViewById(R.id.faultOverlay)
```

Add the effect executor method to `MainActivity`:

```kotlin
    /** Applies the state machine's decisions. Main thread only. */
    private fun applyAlertEffects(effects: List<AlertEffect>) {
        for (effect in effects) {
            when (effect) {
                AlertEffect.SHOW_FAULT_SCREEN -> faultOverlay.visibility = View.VISIBLE
                AlertEffect.HIDE_FAULT_SCREEN -> faultOverlay.visibility = View.GONE
                AlertEffect.SEND_DISCONNECT_ALERT ->
                    discordNotifier.send(AlertMessages.disconnect(isDemoModeEnabled()), mentionEveryone = true)
                AlertEffect.SEND_ALL_CLEAR ->
                    discordNotifier.send(AlertMessages.allClear(isDemoModeEnabled()), mentionEveryone = false)
            }
        }
    }
```

> **Dependency note:** `isDemoModeEnabled()` arrives in Task 7. If implementing Task 6 first, temporarily use the literal `false` in both calls and replace it in Task 7.

Replace the existing `heartbeatRunnable` (lines 105-111) with:

```kotlin
    // Heartbeat runnable: updates the watchdog preference and drives the alert
    // state machine's clock. 5s granularity against a 30s debounce is plenty,
    // and reusing this runnable avoids introducing another thread.
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            WatchdogScheduler.updateHeartbeat(this@MainActivity)
            applyAlertEffects(cameraAlertState.onTick(System.currentTimeMillis()))
            mainHandler.postDelayed(this, 5000)
        }
    }
```

- [ ] **Step 8: Hook the USB callbacks**

In `mOnDeviceConnectListener` (lines 641-672), replace `onConnect`, `onDisconnect` and `onDettach` with:

```kotlin
            override fun onConnect(
                device: UsbDevice?,
                ctrlBlock: UsbControlBlock?,
                createNew: Boolean
            ) {
                mCameraHandler!!.open(ctrlBlock)
                startPreview()
                applyAlertEffects(cameraAlertState.onCameraBack(System.currentTimeMillis()))
            }

            override fun onDisconnect(device: UsbDevice?, ctrlBlock: UsbControlBlock?) {
                if (mCameraHandler != null) {
                    mCameraHandler!!.close()
                }
                // Idempotent: onDisconnect and onDettach both fire for one unplug.
                applyAlertEffects(cameraAlertState.onCameraLost(System.currentTimeMillis()))
            }

            override fun onDettach(device: UsbDevice?) {
                Toast.makeText(this@MainActivity, "Camera Disconnected", Toast.LENGTH_SHORT).show()
                recordCameraError("onDettach at ${System.currentTimeMillis()}")
                applyAlertEffects(cameraAlertState.onCameraLost(System.currentTimeMillis()))
            }
```

- [ ] **Step 9: Build and verify the full chain on the device**

Run: `./gradlew :app:installDebug`

1. With the camera connected and the booth idle, **unplug the webcam.**
2. **Expected, immediately:** the screen turns dark with *"Booth needs a battery swap, find someone to help"*, and taps do nothing.
3. **Expected, within ~5 seconds:** nothing in Discord yet.
4. Plug it back in **within 20 seconds**. **Expected:** overlay clears, preview returns, and **no Discord message was ever sent** — this is the wobble case.
5. Unplug again and **wait 40 seconds.** **Expected:** one `@everyone` message arrives in Discord.
6. Plug back in. **Expected:** overlay clears and an all-clear message arrives with no mention.
7. Repeat the unplug once more. **Expected:** it alerts again (the machine resets after recovery).

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/ryanpudd/photobooth/AlertMessages.kt app/src/test/java/com/ryanpudd/photobooth/AlertMessagesTest.kt app/src/main/res/layout/activity_main.xml app/src/main/res/values/strings.xml app/src/main/java/com/ryanpudd/photobooth/MainActivity.kt
git commit -m "feat: alert on camera loss via screen takeover and Discord

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01AGz3hsSLyn8QH3SV5R73V3"
```

---

### Task 7: Demo mode

An auto-capture loop that exercises the whole pipeline for real — real captures, real uploads, real alerts — so the system is verifiable before the day. It doubles as the battery drain test: **the Discord alert's timestamp is the pack runtime measurement.**

**Files:**
- Create: `app/src/main/java/com/ryanpudd/photobooth/S3KeyBuilder.kt`
- Create: `app/src/main/java/com/ryanpudd/photobooth/DemoModeStore.kt`
- Test: `app/src/test/java/com/ryanpudd/photobooth/S3KeyBuilderTest.kt`
- Modify: `app/src/main/java/com/ryanpudd/photobooth/UploadQueueManager.kt` (`nextAvailableFile`)
- Modify: `app/src/main/java/com/ryanpudd/photobooth/S3Uploader.kt` (key construction)
- Modify: `app/src/test/java/com/ryanpudd/photobooth/UploadQueueManagerTest.kt`
- Modify: `app/src/main/res/layout/dialog_admin_settings.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/com/ryanpudd/photobooth/MainActivity.kt` (`storePhoto`, `onCreate`, `onDestroy`, `showAdminSettingsDialog`)

**Interfaces:**
- Consumes: `UploadQueueManager.nextAvailableFile`, `AlertMessages`
- Produces:
  - `UploadQueueManager.DEMO_PREFIX: String`, `UploadQueueManager.isDemoFile(fileName: String): Boolean`
  - `UploadQueueManager.nextAvailableFile(dir: File, timestamp: Date = Date(), demo: Boolean = false): File`
  - `S3KeyBuilder.buildKey(keyPrefix: String, fileName: String): String`
  - `DemoModeStore.isEnabled(context): Boolean`, `DemoModeStore.setEnabled(context, enabled: Boolean)`
  - `MainActivity.isDemoModeEnabled(): Boolean` (consumed by Task 6's `applyAlertEffects`)

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/ryanpudd/photobooth/S3KeyBuilderTest.kt`:

```kotlin
package com.ryanpudd.photobooth

import org.junit.Assert.assertEquals
import org.junit.Test

class S3KeyBuilderTest {

    @Test
    fun noPrefix_usesBareFilename() {
        assertEquals("IMG_20260920_193045_001.jpg", S3KeyBuilder.buildKey("", "IMG_20260920_193045_001.jpg"))
    }

    @Test
    fun prefixIsJoinedWithASingleSlash() {
        assertEquals("wedding/IMG_1.jpg", S3KeyBuilder.buildKey("wedding", "IMG_1.jpg"))
        assertEquals("wedding/IMG_1.jpg", S3KeyBuilder.buildKey("wedding/", "IMG_1.jpg"))
        assertEquals("wedding/IMG_1.jpg", S3KeyBuilder.buildKey("/wedding/", "IMG_1.jpg"))
        assertEquals("wedding/IMG_1.jpg", S3KeyBuilder.buildKey("  wedding  ", "IMG_1.jpg"))
    }

    @Test
    fun demoFilesGetTheirOwnFolderSoTheyAreEasyToDelete() {
        assertEquals("wedding/demo/DEMO_IMG_1.jpg", S3KeyBuilder.buildKey("wedding", "DEMO_IMG_1.jpg"))
    }

    @Test
    fun demoFilesWithNoPrefixStillGetTheDemoFolder() {
        assertEquals("demo/DEMO_IMG_1.jpg", S3KeyBuilder.buildKey("", "DEMO_IMG_1.jpg"))
    }

    @Test
    fun retriedDemoFilesAreStillRecognised() {
        assertEquals("demo/DEMO_IMG_1__u2.jpg", S3KeyBuilder.buildKey("", "DEMO_IMG_1__u2.jpg"))
    }
}
```

Append to `app/src/test/java/com/ryanpudd/photobooth/UploadQueueManagerTest.kt`, inside the existing class:

```kotlin
    @Test
    fun nextAvailableFile_normalMode_hasNoDemoPrefix() {
        val dir = tempFolder.newFolder("pending")
        val file = UploadQueueManager.nextAvailableFile(dir, demo = false)
        assertTrue(file.name.startsWith("IMG_"))
        assertFalse(UploadQueueManager.isDemoFile(file.name))
    }

    @Test
    fun nextAvailableFile_demoMode_isMarkedAsDemo() {
        val dir = tempFolder.newFolder("pending")
        val file = UploadQueueManager.nextAvailableFile(dir, demo = true)
        assertTrue(file.name.startsWith("DEMO_IMG_"))
        assertTrue(UploadQueueManager.isDemoFile(file.name))
    }
```

Add `import org.junit.Assert.assertFalse` to that test file's imports.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "*S3KeyBuilderTest*" --tests "*UploadQueueManagerTest*"`
Expected: FAIL — `Unresolved reference: S3KeyBuilder`, and `nextAvailableFile` has no `demo` parameter

- [ ] **Step 3: Write the key builder**

`app/src/main/java/com/ryanpudd/photobooth/S3KeyBuilder.kt`:

```kotlin
package com.ryanpudd.photobooth

/**
 * Builds the S3 object key for a queued photo. Demo captures land under a
 * `demo/` folder so a test run can be deleted in one click without touching
 * real wedding photos.
 */
object S3KeyBuilder {

    fun buildKey(keyPrefix: String, fileName: String): String {
        val parts = mutableListOf<String>()
        val prefix = keyPrefix.trim().trim('/')
        if (prefix.isNotEmpty()) parts.add(prefix)
        if (UploadQueueManager.isDemoFile(fileName)) parts.add("demo")
        parts.add(fileName)
        return parts.joinToString("/")
    }
}
```

- [ ] **Step 4: Mark demo files in the queue**

In `UploadQueueManager.kt`, add to the object's constants:

```kotlin
    const val DEMO_PREFIX = "DEMO_"
```

Add the predicate:

```kotlin
    /** True when [fileName] came from a demo-mode capture. */
    fun isDemoFile(fileName: String): Boolean = fileName.startsWith(DEMO_PREFIX)
```

Replace `nextAvailableFile` with:

```kotlin
    /** Collision-safe [DEMO_]IMG_yyyyMMdd_HHmmss_NNN.jpg name inside [dir]. */
    fun nextAvailableFile(dir: File, timestamp: Date = Date(), demo: Boolean = false): File {
        val stamp = FILENAME_DATE_FORMAT.format(timestamp)
        val prefix = if (demo) DEMO_PREFIX else ""
        var counter = 1
        while (true) {
            val candidate = File(dir, "%sIMG_%s_%03d.jpg".format(prefix, stamp, counter))
            if (!candidate.exists()) return candidate
            counter++
        }
    }
```

- [ ] **Step 5: Route demo files in the uploader**

In `S3Uploader.kt`, replace the `val key = ...` line with:

```kotlin
            val key = S3KeyBuilder.buildKey(config.keyPrefix, file.name)
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS — all tests including the 5 new `S3KeyBuilderTest` cases and the 2 new queue cases

- [ ] **Step 7: Write the demo mode store**

`app/src/main/java/com/ryanpudd/photobooth/DemoModeStore.kt`:

```kotlin
package com.ryanpudd.photobooth

import android.content.Context

/**
 * Demo mode toggle. Plain SharedPreferences, not encrypted: it is a switch,
 * not a secret. Persists across restarts by design - the overnight drain test
 * needs it to survive, and setup happens on the day so it will be noticed.
 */
object DemoModeStore {
    private const val PREFS_NAME = "demo_mode_prefs"
    private const val KEY_ENABLED = "demo_mode_enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
```

- [ ] **Step 8: Add the demo toggle to the admin dialog**

In `app/src/main/res/values/strings.xml`, before `</resources>`:

```xml
    <string name="label_demo_mode">Demo mode (auto-capture every minute)</string>
    <string name="demo_mode_banner">DEMO MODE</string>
```

In `app/src/main/res/layout/dialog_admin_settings.xml`, after the `btnSendDiagnostics` Button:

```xml
        <CheckBox
            android:id="@+id/checkDemoMode"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:text="@string/label_demo_mode" />
```

In `app/src/main/res/layout/activity_main.xml`, after the `faultOverlay` TextView:

```xml
    <TextView
        android:id="@+id/demoBanner"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:background="#CCAA0000"
        android:padding="8dp"
        android:text="@string/demo_mode_banner"
        android:textColor="#FFFFFF"
        android:textSize="16sp"
        android:textStyle="bold"
        android:visibility="gone" />
```

- [ ] **Step 9: Wire demo mode into MainActivity**

Add imports:

```kotlin
import android.widget.CheckBox
```

Add fields:

```kotlin
    private lateinit var demoBanner: TextView
    private val demoCaptureRunnable = object : Runnable {
        override fun run() {
            if (isDemoModeEnabled() &&
                currentState == BoothState.IDLE &&
                mCameraHandler?.isPreviewing == true
            ) {
                startPreCaptureCountdown()
            }
            mainHandler.postDelayed(this, DEMO_CAPTURE_INTERVAL_MS)
        }
    }
```

Add to the `companion object`:

```kotlin
        private const val DEMO_CAPTURE_INTERVAL_MS = 60_000L
```

Add the accessor and banner sync:

```kotlin
    fun isDemoModeEnabled(): Boolean = DemoModeStore.isEnabled(this)

    private fun syncDemoBanner() {
        demoBanner.visibility = if (isDemoModeEnabled()) View.VISIBLE else View.GONE
    }
```

In `onCreate()`, alongside the other `findViewById` calls:

```kotlin
        demoBanner = findViewById(R.id.demoBanner)
        syncDemoBanner()
        mainHandler.postDelayed(demoCaptureRunnable, DEMO_CAPTURE_INTERVAL_MS)
```

In `onDestroy()`, alongside the other `removeCallbacks` calls:

```kotlin
        mainHandler.removeCallbacks(demoCaptureRunnable)
```

In `storePhoto()`, replace the existing `nextAvailableFile` call:

```kotlin
            val finalFile = UploadQueueManager.nextAvailableFile(pendingDir)
```

with:

```kotlin
            val finalFile = UploadQueueManager.nextAvailableFile(pendingDir, demo = isDemoModeEnabled())
```

In `showAdminSettingsDialog()`, after the `btnSendDiagnostics` wiring:

```kotlin
        val checkDemoMode = view.findViewById<CheckBox>(R.id.checkDemoMode)
        checkDemoMode.isChecked = isDemoModeEnabled()
```

Inside the `setPositiveButton(R.string.save)` lambda, after the webhook block:

```kotlin
                DemoModeStore.setEnabled(this, checkDemoMode.isChecked)
                syncDemoBanner()
```

Finally, replace the two `isDemoModeEnabled()` placeholders in `applyAlertEffects` (Task 6, Step 7) if they were left as `false`.

- [ ] **Step 10: Build and verify on the device**

Run: `./gradlew :app:installDebug`

1. Open admin, tick **Demo mode**, Save.
2. **Expected:** a red `DEMO MODE` banner appears on the booth screen.
3. Leave it alone for three minutes. **Expected:** it captures roughly once a minute on its own.
4. Check S3. **Expected:** objects under `<your prefix>/demo/DEMO_IMG_*.jpg`, and no demo files mixed in with real photos.
5. Unplug the webcam and wait 40 seconds. **Expected:** the Discord alert arrives **prefixed `[TEST]`**.
6. Plug back in. **Expected:** `[TEST]`-prefixed all-clear.
7. Open admin, untick Demo mode, Save. **Expected:** banner disappears, auto-capture stops.

- [ ] **Step 11: Commit**

```bash
git add app/src/main/java/com/ryanpudd/photobooth/S3KeyBuilder.kt app/src/main/java/com/ryanpudd/photobooth/DemoModeStore.kt app/src/test/java/com/ryanpudd/photobooth/S3KeyBuilderTest.kt app/src/test/java/com/ryanpudd/photobooth/UploadQueueManagerTest.kt app/src/main/java/com/ryanpudd/photobooth/UploadQueueManager.kt app/src/main/java/com/ryanpudd/photobooth/S3Uploader.kt app/src/main/res/layout/dialog_admin_settings.xml app/src/main/res/layout/activity_main.xml app/src/main/res/values/strings.xml app/src/main/java/com/ryanpudd/photobooth/MainActivity.kt
git commit -m "feat: add demo mode with demo/ S3 prefix and [TEST] alerts

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01AGz3hsSLyn8QH3SV5R73V3"
```

---

### Task 8: Screen brightness

Pinned to 100% in normal use so the drain test transfers literally rather than approximately, dimmed to 30% after 30s idle to save power, and never dimmed in demo mode so the measurement stays a true worst case.

**Files:**
- Create: `app/src/main/java/com/ryanpudd/photobooth/BrightnessPolicy.kt`
- Test: `app/src/test/java/com/ryanpudd/photobooth/BrightnessPolicyTest.kt`
- Modify: `app/src/main/java/com/ryanpudd/photobooth/MainActivity.kt` (`onCreate`, `heartbeatRunnable`, new `dispatchTouchEvent`)

**Interfaces:**
- Consumes: `MainActivity.isDemoModeEnabled()`
- Produces: `BrightnessPolicy.brightnessFor(demoMode: Boolean, isIdle: Boolean, msSinceLastTouch: Long): Float`, plus `BrightnessPolicy.ACTIVE`, `DIMMED`, `IDLE_DIM_AFTER_MS`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/ryanpudd/photobooth/BrightnessPolicyTest.kt`:

```kotlin
package com.ryanpudd.photobooth

import org.junit.Assert.assertEquals
import org.junit.Test

class BrightnessPolicyTest {

    @Test
    fun constantsMatchTheAgreedValues() {
        assertEquals(1.0f, BrightnessPolicy.ACTIVE, 0.0001f)
        assertEquals(0.3f, BrightnessPolicy.DIMMED, 0.0001f)
        assertEquals(30_000L, BrightnessPolicy.IDLE_DIM_AFTER_MS)
    }

    @Test
    fun idleBeyondTimeout_dims() {
        assertEquals(
            BrightnessPolicy.DIMMED,
            BrightnessPolicy.brightnessFor(demoMode = false, isIdle = true, msSinceLastTouch = 31_000L),
            0.0001f
        )
    }

    @Test
    fun idleWithinTimeout_staysBright() {
        assertEquals(
            BrightnessPolicy.ACTIVE,
            BrightnessPolicy.brightnessFor(demoMode = false, isIdle = true, msSinceLastTouch = 29_000L),
            0.0001f
        )
    }

    @Test
    fun exactlyAtTheTimeout_dims() {
        assertEquals(
            BrightnessPolicy.DIMMED,
            BrightnessPolicy.brightnessFor(demoMode = false, isIdle = true, msSinceLastTouch = 30_000L),
            0.0001f
        )
    }

    @Test
    fun midCaptureNeverDims_howeverLongSinceTheLastTouch() {
        assertEquals(
            BrightnessPolicy.ACTIVE,
            BrightnessPolicy.brightnessFor(demoMode = false, isIdle = false, msSinceLastTouch = 600_000L),
            0.0001f
        )
    }

    @Test
    fun demoModeNeverDims_soTheDrainTestStaysAWorstCase() {
        assertEquals(
            BrightnessPolicy.ACTIVE,
            BrightnessPolicy.brightnessFor(demoMode = true, isIdle = true, msSinceLastTouch = 600_000L),
            0.0001f
        )
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*BrightnessPolicyTest*"`
Expected: FAIL — `Unresolved reference: BrightnessPolicy`

- [ ] **Step 3: Write the implementation**

`app/src/main/java/com/ryanpudd/photobooth/BrightnessPolicy.kt`:

```kotlin
package com.ryanpudd.photobooth

/**
 * Decides screen brightness.
 *
 * Pinned to full in normal use so the battery drain test transfers literally
 * rather than approximately, and so the booth looks the same all evening
 * regardless of what auto-brightness thinks of a dim venue.
 *
 * Demo mode never dims: the drain test is meant to be the worst case.
 */
object BrightnessPolicy {

    const val ACTIVE = 1.0f
    const val DIMMED = 0.3f
    const val IDLE_DIM_AFTER_MS = 30_000L

    fun brightnessFor(demoMode: Boolean, isIdle: Boolean, msSinceLastTouch: Long): Float {
        if (demoMode) return ACTIVE
        if (!isIdle) return ACTIVE
        return if (msSinceLastTouch >= IDLE_DIM_AFTER_MS) DIMMED else ACTIVE
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*BrightnessPolicyTest*"`
Expected: PASS, 6 tests

- [ ] **Step 5: Apply brightness in MainActivity**

Add the import:

```kotlin
import android.view.MotionEvent
```

Add fields:

```kotlin
    private var lastTouchElapsedMs = SystemClock.elapsedRealtime()
    private var appliedBrightness = -1f
```

Add the apply method:

```kotlin
    /**
     * Window-level brightness only: needs no permission, touches nothing
     * system-wide, and Android restores it if the app loses focus or dies.
     */
    private fun applyBrightness() {
        val target = BrightnessPolicy.brightnessFor(
            demoMode = isDemoModeEnabled(),
            isIdle = currentState == BoothState.IDLE,
            msSinceLastTouch = SystemClock.elapsedRealtime() - lastTouchElapsedMs
        )
        if (target == appliedBrightness) return
        appliedBrightness = target
        window.attributes = window.attributes.apply { screenBrightness = target }
    }
```

Override touch dispatch so any touch wakes the screen. Returning `super` means the tap still reaches the status overlay, so **one tap both wakes and starts the countdown**:

```kotlin
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        lastTouchElapsedMs = SystemClock.elapsedRealtime()
        applyBrightness()
        return super.dispatchTouchEvent(ev)
    }
```

In `onCreate()`, after `setContentView`:

```kotlin
        applyBrightness()
```

In `heartbeatRunnable`, after the `applyAlertEffects(...)` line added in Task 6:

```kotlin
            applyBrightness()
```

- [ ] **Step 6: Build and verify on the device**

Run: `./gradlew :app:installDebug`

1. Leave the booth idle for 30 seconds. **Expected:** the screen dims noticeably but stays clearly readable — never black.
2. Tap once. **Expected:** full brightness returns instantly **and** the photo countdown starts. One tap, not two.
3. Start a capture and let the countdown and review run past 30 seconds without touching. **Expected:** it does not dim mid-photo.
4. Enable demo mode and leave it for five minutes. **Expected:** it never dims.
5. Exit the app to the launcher. **Expected:** system brightness is unchanged — the override was window-level only.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/ryanpudd/photobooth/BrightnessPolicy.kt app/src/test/java/com/ryanpudd/photobooth/BrightnessPolicyTest.kt app/src/main/java/com/ryanpudd/photobooth/MainActivity.kt
git commit -m "feat: pin brightness at 100% with 30s idle dim

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01AGz3hsSLyn8QH3SV5R73V3"
```

---

### Task 9: Battery drain test runbook

Not code — the procedure that produces the number every deferred decision is waiting on. Committed to the repo so the result is recorded rather than remembered.

**Files:**
- Create: `docs/superpowers/runbooks/battery-drain-test.md`

**Interfaces:**
- Consumes: demo mode (Task 7), Discord alerting (Task 6)
- Produces: a measured pack runtime figure, which decides the camera cooldown and preview resolution questions

- [ ] **Step 1: Write the runbook**

`docs/superpowers/runbooks/battery-drain-test.md`:

```markdown
# Battery Drain Test

## What this measures

How long the USB battery pack keeps the Logitech BRIO alive under a worst-case
load. The webcam dies when the pack dies, which fires the Discord alert — so
**the alert's timestamp minus the start time is the runtime.** No stopwatch,
no laptop, nobody watching.

## Why it is a worst case

- Demo mode captures once a minute all night; real use is bursty and lighter.
- Demo mode pins brightness to 100% and suppresses idle dimming.
- The BRIO requests the full 500 mA (`max_power=500` in `dumpsys usb`).

A result here is therefore a **floor**. Real-world runtime should beat it.

## Procedure

1. Charge the battery pack fully. Record its capacity in Wh or mAh below.
2. Charge the tablet to 100%.
3. Connect: pack -> OTG splitter -> tablet + BRIO.
4. Launch the booth. Confirm the preview is live.
5. Admin (long-press, gear, PIN) -> **Send test alert**. Confirm it lands in
   Discord on every helper's phone. **Do not skip this** - it verifies the
   measuring instrument before the measurement.
6. Admin -> tick **Demo mode** -> Save. Confirm the red `DEMO MODE` banner.
7. **Record the start time.** Walk away. Leave it overnight if needed.
8. When the pack dies, the webcam drops and a `[TEST]`-prefixed alert arrives.
   **That message's timestamp is the end time.**
9. Record the result below.
10. Admin -> untick Demo mode -> Save. Delete the `demo/` folder from S3.

## Result

| Field | Value |
|---|---|
| Date of test | |
| Pack make / capacity | |
| Start time | |
| Alert timestamp | |
| **Measured runtime** | |
| Tablet battery % at alert | |

## What the result decides

- **Comfortably clears the evening** -> change nothing. Do not build the camera
  cooldown; it adds risk for no benefit.
- **Marginal** -> lower the *preview* resolution (captures stay full res). Do
  this before considering the camera cooldown.
- **Well short** -> a bigger or second pack is the cheapest fix. A spare pack
  is what makes the alert actionable in the first place.

> **Do not build the camera cooldown without camera-health detection first.**
> If the app switches the camera off deliberately and it fails to wake,
> `onDettach` never fires, so the booth sits looking healthy while being dead
> and nothing alerts anyone. See the spec's "Key design constraint".
```

- [ ] **Step 2: Commit**

```bash
git add docs/superpowers/runbooks/battery-drain-test.md
git commit -m "docs: add battery drain test runbook

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01AGz3hsSLyn8QH3SV5R73V3"
```

---

## Final verification

- [ ] Run the whole unit suite: `./gradlew :app:testDebugUnitTest` — expected PASS. **39 new tests**: 8 `DiscordPayloadTest`, 6 `BoothDiagnosticsTest`, 9 `CameraAlertStateTest`, 5 `AlertMessagesTest`, 5 `S3KeyBuilderTest`, 6 `BrightnessPolicyTest`, plus 2 added to the existing `UploadQueueManagerTest`. All pre-existing `UploadQueueManagerTest` cases must still pass unchanged.
- [ ] Build a release-shaped APK: `./gradlew :app:assembleDebug` — expected BUILD SUCCESSFUL
- [ ] Full rehearsal on the real hardware: booth running on the pack, **physically pull the pack**, and confirm the screen changes and every helper's phone buzzes. A test button alone leaves the trigger path unverified.

## Deferred, pending diagnostics and the drain test

These were deliberately excluded and should only be revisited with data in hand:

| Deferred | Unblocked by |
|---|---|
| Camera-health watchdog (frame counter) | Task 4 diagnostics showing whether `isPreviewing` lies during a stall |
| Auto-recovery ladder (restart, cap attempts) | Same |
| Camera idle cooldown | Task 9 runtime figure **and** camera-health detection |
| Lower preview resolution | Task 9 runtime figure |
