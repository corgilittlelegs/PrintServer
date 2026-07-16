# USB → Network Print Server (Android) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Android app that exposes an IPP-USB printer plugged into the phone as a driverless IPP Everywhere network printer (plus a raw port-9100 fallback for non-IPP-USB printers).

**Architecture:** Foreground service hosting a protocol-aware byte relay: mDNS advertises the printer, an HTTP relay on :8631 pipes IPP transactions between LAN clients and the printer's IPP-USB channels, and a raw :9100 relay covers legacy printers. Clients render documents themselves; the app never parses document content. See spec: `docs/superpowers/specs/2026-07-16-usb-ipp-print-server-design.md`.

**Tech Stack:** Kotlin + coroutines, single-module Android app, min SDK 26, target SDK 35. Only non-AndroidX dependency: HP JIPP (`com.hp.jipp:jipp-core`) for the one-time attribute query. Unit tests are plain JVM JUnit 4 tests (no device needed) — all protocol logic is pure Kotlin behind a `UsbTransport` interface.

**Git note:** User deferred `git init`. Before Task 1, ask the user whether to initialize the repo; if they still defer, skip every "Commit" step but keep all other steps.

**Build prerequisite:** Android SDK installed with `local.properties` pointing at it (`sdk.dir=...`) or `ANDROID_HOME` set. All test commands run on the JVM — no emulator or device required until Task 14.

**Package:** `dev.jaspreet.printserver` (rename before Play Store if desired). Source root abbreviated below as `SRC = app/src/main/java/dev/jaspreet/printserver`, tests as `TST = app/src/test/java/dev/jaspreet/printserver`.

---

### Task 1: Project scaffold

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `SRC/MainActivity.kt`
- Create: `app/src/main/res/layout/activity_main.xml`
- Create: `app/src/main/res/values/strings.xml`
- Create: `.gitignore`

- [ ] **Step 1: Write Gradle files**

`settings.gradle.kts`:
```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "PrintServer"
include(":app")
```

`build.gradle.kts` (root):
```kotlin
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
}
```

`gradle.properties`:
```properties
android.useAndroidX=true
org.gradle.jvmargs=-Xmx2g
```

`app/build.gradle.kts`:
```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.jaspreet.printserver"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.jaspreet.printserver"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.hp.jipp:jipp-core:0.7.16")
    testImplementation("junit:junit:4.13.2")
}
```

`.gitignore`:
```
.gradle/
build/
local.properties
.idea/
*.iml
```

- [ ] **Step 2: Write minimal manifest, activity, layout**

`app/src/main/AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-feature android:name="android.hardware.usb.host" android:required="true" />

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application
        android:label="@string/app_name"
        android:theme="@style/Theme.AppCompat.DayNight">

        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`SRC/MainActivity.kt`:
```kotlin
package dev.jaspreet.printserver

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }
}
```

`app/src/main/res/layout/activity_main.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="16dp">

    <TextView
        android:id="@+id/statusText"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="@string/status_idle"
        android:textSize="18sp" />
</LinearLayout>
```

`app/src/main/res/values/strings.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">PrintServer</string>
    <string name="status_idle">Idle — plug in a USB printer</string>
</resources>
```

- [ ] **Step 3: Add Gradle wrapper and verify build**

Run:
```bash
cd /Users/jaspreet/Documents/Personal/PrintServer
gradle wrapper --gradle-version 8.9   # or copy a wrapper from another project
./gradlew :app:assembleDebug
```
Expected: `BUILD SUCCESSFUL`. If `SDK location not found`, create `local.properties` with `sdk.dir=/Users/jaspreet/Library/Android/sdk`.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "chore: Android project scaffold"
```

---

### Task 2: UsbTransport abstraction + streams + fakes

The seam that makes everything testable. All protocol code talks to `UsbTransport`, never to Android USB APIs.

**Files:**
- Create: `SRC/usb/UsbTransport.kt`
- Create: `SRC/usb/UsbTransportStreams.kt`
- Create: `TST/usb/FakePrinterTransport.kt`
- Test: `TST/usb/UsbTransportStreamsTest.kt`

- [ ] **Step 1: Write the failing tests**

`TST/usb/UsbTransportStreamsTest.kt`:
```kotlin
package dev.jaspreet.printserver.usb

import org.junit.Assert.assertEquals
import org.junit.Test

class UsbTransportStreamsTest {

    @Test
    fun `input stream reads across transport packet boundaries`() {
        val fake = FakePrinterTransport { "HELLOWORLD".toByteArray() }
        fake.write("x".toByteArray(), 0, 1) // trigger a pending response
        val input = UsbTransportInputStream(fake)
        val out = ByteArray(10)
        var read = 0
        while (read < 10) {
            val n = input.read(out, read, 10 - read)
            if (n < 0) break
            read += n
        }
        assertEquals("HELLOWORLD", String(out, 0, read))
    }

    @Test
    fun `output stream forwards writes to transport`() {
        val fake = FakePrinterTransport { ByteArray(0) }
        val output = UsbTransportOutputStream(fake)
        output.write("abc".toByteArray())
        output.flush()
        assertEquals("abc", String(fake.lastRequest()))
    }
}
```

`TST/usb/FakePrinterTransport.kt` (test utility, not a test):
```kotlin
package dev.jaspreet.printserver.usb

import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Scripted printer: buffers writes; on the first read after a write burst,
 * calls [respond] with everything written so far and serves the reply bytes.
 */
class FakePrinterTransport(private val respond: (ByteArray) -> ByteArray) : UsbTransport {
    private val reqBuf = ByteArrayOutputStream()
    private var lastReq: ByteArray = ByteArray(0)
    private var pending: ByteArray = ByteArray(0)
    private var pos = 0
    var closed = false
        private set

    fun lastRequest(): ByteArray = lastReq

    @Synchronized
    override fun write(data: ByteArray, offset: Int, length: Int) {
        if (closed) throw IOException("closed")
        reqBuf.write(data, offset, length)
        lastReq = reqBuf.toByteArray()
    }

    @Synchronized
    override fun read(buffer: ByteArray): Int {
        if (closed) throw IOException("closed")
        if (pos >= pending.size) {
            if (reqBuf.size() == 0) throw IOException("fake printer: nothing to respond to")
            pending = respond(reqBuf.toByteArray())
            reqBuf.reset()
            pos = 0
        }
        val n = minOf(buffer.size, pending.size - pos)
        System.arraycopy(pending, pos, buffer, 0, n)
        pos += n
        return n
    }

    @Synchronized
    override fun close() { closed = true }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.usb.*"`
Expected: compilation FAILURE — `UsbTransport`, `UsbTransportInputStream` not defined.

- [ ] **Step 3: Write the implementation**

`SRC/usb/UsbTransport.kt`:
```kotlin
package dev.jaspreet.printserver.usb

/**
 * One printer byte channel: for IPP-USB, a claimed USB interface pair
 * (bulk OUT + bulk IN). All methods are blocking.
 */
interface UsbTransport {
    /** Writes exactly [length] bytes from [data] starting at [offset]. Throws IOException on failure. */
    fun write(data: ByteArray, offset: Int, length: Int)

    /** Reads at least 1 byte into [buffer], returns count. Throws IOException on failure or timeout. */
    fun read(buffer: ByteArray): Int

    fun close()
}
```

`SRC/usb/UsbTransportStreams.kt`:
```kotlin
package dev.jaspreet.printserver.usb

import java.io.InputStream
import java.io.OutputStream

class UsbTransportInputStream(private val transport: UsbTransport) : InputStream() {
    private val buf = ByteArray(16384)
    private var pos = 0
    private var end = 0

    private fun fill(): Boolean {
        if (pos < end) return true
        end = transport.read(buf)
        pos = 0
        return end > 0
    }

    override fun read(): Int {
        if (!fill()) return -1
        return buf[pos++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!fill()) return -1
        val n = minOf(len, end - pos)
        System.arraycopy(buf, pos, b, off, n)
        pos += n
        return n
    }
}

class UsbTransportOutputStream(private val transport: UsbTransport) : OutputStream() {
    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
    override fun write(b: ByteArray, off: Int, len: Int) = transport.write(b, off, len)
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.usb.*"`
Expected: 2 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: UsbTransport abstraction with stream adapters and test fake"
```

---

### Task 3: HTTP head parser

**Files:**
- Create: `SRC/http/HttpHead.kt`
- Test: `TST/http/HttpHeadTest.kt`

- [ ] **Step 1: Write the failing tests**

`TST/http/HttpHeadTest.kt`:
```kotlin
package dev.jaspreet.printserver.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

class HttpHeadTest {

    private fun stream(s: String) = ByteArrayInputStream(s.toByteArray(Charsets.ISO_8859_1))

    @Test
    fun `parses request line and headers`() {
        val head = HttpHead.parse(stream("POST /ipp/print HTTP/1.1\r\nHost: pc:8631\r\nContent-Length: 42\r\n\r\n"))!!
        assertEquals("POST /ipp/print HTTP/1.1", head.startLine)
        assertEquals("pc:8631", head.get("host"))          // case-insensitive lookup
        assertEquals("42", head.get("Content-Length"))
    }

    @Test
    fun `returns null on immediate EOF`() {
        assertNull(HttpHead.parse(stream("")))
    }

    @Test
    fun `set replaces header case-insensitively`() {
        val head = HttpHead.parse(stream("GET / HTTP/1.1\r\nHOST: a\r\n\r\n"))!!
        head.set("Host", "localhost")
        assertEquals("localhost", head.get("host"))
        val text = String(head.serialize(), Charsets.ISO_8859_1)
        assertEquals(1, Regex("(?im)^host:").findAll(text).count())
    }

    @Test
    fun `serialize round-trips`() {
        val original = "POST /ipp/print HTTP/1.1\r\nHost: x\r\nContent-Type: application/ipp\r\n\r\n"
        val head = HttpHead.parse(stream(original))!!
        assertEquals(original, String(head.serialize(), Charsets.ISO_8859_1))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.http.HttpHeadTest"`
Expected: compilation FAILURE — `HttpHead` not defined.

- [ ] **Step 3: Write the implementation**

`SRC/http/HttpHead.kt`:
```kotlin
package dev.jaspreet.printserver.http

import java.io.IOException
import java.io.InputStream

/** Parsed HTTP request or response head (start line + headers, no body). */
class HttpHead(val startLine: String, headers: List<Pair<String, String>>) {
    private val headers = headers.toMutableList()

    fun get(name: String): String? =
        headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    fun set(name: String, value: String) {
        headers.removeAll { it.first.equals(name, ignoreCase = true) }
        headers.add(name to value)
    }

    fun serialize(): ByteArray = buildString {
        append(startLine).append("\r\n")
        headers.forEach { (n, v) -> append(n).append(": ").append(v).append("\r\n") }
        append("\r\n")
    }.toByteArray(Charsets.ISO_8859_1)

    companion object {
        /** Returns null if the stream is at EOF before any byte of the start line. */
        fun parse(input: InputStream): HttpHead? {
            val start = readLine(input) ?: return null
            val list = mutableListOf<Pair<String, String>>()
            while (true) {
                val line = readLine(input) ?: throw IOException("EOF inside HTTP headers")
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx <= 0) throw IOException("Malformed header: $line")
                list += line.substring(0, idx).trim() to line.substring(idx + 1).trim()
            }
            return HttpHead(start, list)
        }

        /** Reads one CRLF- (or LF-) terminated line; null on EOF at line start. */
        fun readLine(input: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val c = input.read()
                if (c == -1) return if (sb.isEmpty()) null else sb.toString()
                if (c == '\n'.code) return sb.toString()
                if (c != '\r'.code) sb.append(c.toChar())
            }
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.http.HttpHeadTest"`
Expected: 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: HTTP head parser/serializer"
```

---

### Task 4: Body copier (Content-Length + chunked framing)

Framing awareness is what lets the relay know when a transaction is over so the USB channel can be released clean.

**Files:**
- Create: `SRC/http/BodyCopier.kt`
- Test: `TST/http/BodyCopierTest.kt`

- [ ] **Step 1: Write the failing tests**

`TST/http/BodyCopierTest.kt`:
```kotlin
package dev.jaspreet.printserver.http

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class BodyCopierTest {

    private fun head(vararg headers: Pair<String, String>) =
        HttpHead("POST / HTTP/1.1", headers.toList())

    private fun copy(head: HttpHead, body: String): Pair<String, String> {
        val input = ByteArrayInputStream((body + "LEFTOVER").toByteArray(Charsets.ISO_8859_1))
        val out = ByteArrayOutputStream()
        BodyCopier.copy(head, input, out)
        val remaining = input.readBytes().toString(Charsets.ISO_8859_1)
        return out.toString("ISO-8859-1") to remaining
    }

    @Test
    fun `copies exactly content-length bytes`() {
        val (copied, remaining) = copy(head("Content-Length" to "5"), "hello")
        assertEquals("hello", copied)
        assertEquals("LEFTOVER", remaining)  // did not over-read
    }

    @Test
    fun `no framing headers means no body`() {
        val (copied, remaining) = copy(head(), "")
        assertEquals("", copied)
        assertEquals("LEFTOVER", remaining)
    }

    @Test
    fun `copies chunked body verbatim including terminator`() {
        val chunked = "4\r\nWiki\r\n5\r\npedia\r\n0\r\n\r\n"
        val (copied, remaining) = copy(head("Transfer-Encoding" to "chunked"), chunked)
        assertEquals(chunked, copied)
        assertEquals("LEFTOVER", remaining)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.http.BodyCopierTest"`
Expected: compilation FAILURE — `BodyCopier` not defined.

- [ ] **Step 3: Write the implementation**

`SRC/http/BodyCopier.kt`:
```kotlin
package dev.jaspreet.printserver.http

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Copies exactly one HTTP message body from [from] to [to], using the framing
 * declared in [head] (Content-Length or chunked). Chunked bodies are copied
 * verbatim — framing bytes included — so the receiver can re-parse them.
 * IPP-USB responses are always framed; a head with neither header has no body.
 */
object BodyCopier {
    private val CRLF = "\r\n".toByteArray(Charsets.ISO_8859_1)

    fun copy(head: HttpHead, from: InputStream, to: OutputStream) {
        val te = head.get("Transfer-Encoding")
        if (te != null && te.contains("chunked", ignoreCase = true)) {
            copyChunked(from, to)
            return
        }
        val length = head.get("Content-Length")?.trim()?.toLongOrNull() ?: 0L
        if (length > 0) copyExact(from, to, length)
    }

    private fun copyExact(from: InputStream, to: OutputStream, count: Long) {
        val buf = ByteArray(65536)
        var left = count
        while (left > 0) {
            val n = from.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
            if (n < 0) throw IOException("EOF mid-body, expected $left more bytes")
            to.write(buf, 0, n)
            left -= n
        }
    }

    private fun copyChunked(from: InputStream, to: OutputStream) {
        while (true) {
            val sizeLine = HttpHead.readLine(from) ?: throw IOException("EOF at chunk size")
            to.write(sizeLine.toByteArray(Charsets.ISO_8859_1)); to.write(CRLF)
            val size = sizeLine.substringBefore(';').trim().toLongOrNull(16)
                ?: throw IOException("Bad chunk size: $sizeLine")
            if (size > 0) {
                copyExact(from, to, size)
                expectCrlf(from)
                to.write(CRLF)
            } else {
                // trailer section: copy lines until the empty terminator line
                while (true) {
                    val line = HttpHead.readLine(from) ?: throw IOException("EOF in trailers")
                    to.write(line.toByteArray(Charsets.ISO_8859_1)); to.write(CRLF)
                    if (line.isEmpty()) return
                }
            }
        }
    }

    private fun expectCrlf(from: InputStream) {
        val cr = from.read(); val lf = from.read()
        if (cr != '\r'.code || lf != '\n'.code) throw IOException("Missing CRLF after chunk data")
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.http.BodyCopierTest"`
Expected: 3 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: framing-aware HTTP body copier"
```

---

### Task 5: Channel pool

**Files:**
- Create: `SRC/relay/ChannelPool.kt`
- Test: `TST/relay/ChannelPoolTest.kt`

- [ ] **Step 1: Write the failing tests**

`TST/relay/ChannelPoolTest.kt`:
```kotlin
package dev.jaspreet.printserver.relay

import dev.jaspreet.printserver.usb.FakePrinterTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class ChannelPoolTest {

    private fun fake() = FakePrinterTransport { ByteArray(0) }

    @Test
    fun `lease returns released channel`() {
        val a = fake()
        val pool = ChannelPool(listOf(a))
        val leased = pool.lease(1000)
        assertSame(a, leased)
        pool.release(leased)
        assertSame(a, pool.lease(1000))
    }

    @Test(expected = IOException::class)
    fun `lease times out when all channels busy`() {
        val pool = ChannelPool(listOf(fake()))
        pool.lease(100)
        pool.lease(100) // no release -> must throw
    }

    @Test
    fun `discard closes channel and signals when none left`() {
        val a = fake()
        val dead = AtomicBoolean(false)
        val pool = ChannelPool(listOf(a))
        pool.onAllChannelsDead = { dead.set(true) }
        pool.discard(pool.lease(1000))
        assertTrue(a.closed)
        assertTrue(dead.get())
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.relay.ChannelPoolTest"`
Expected: compilation FAILURE — `ChannelPool` not defined.

- [ ] **Step 3: Write the implementation**

`SRC/relay/ChannelPool.kt`:
```kotlin
package dev.jaspreet.printserver.relay

import dev.jaspreet.printserver.usb.UsbTransport
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pool of exclusive IPP-USB channels. IPP-USB rule: one complete HTTP
 * request/response per channel at a time. Lease before forwarding a
 * transaction; release only after the response fully streamed; discard
 * (never release) a channel that may hold a half-finished transaction.
 */
class ChannelPool(transports: List<UsbTransport>) {
    private val queue = ArrayBlockingQueue<UsbTransport>(maxOf(transports.size, 1))
    private val alive = AtomicInteger(transports.size)

    /** Invoked once when the last channel is discarded (printer needs reconnect). */
    var onAllChannelsDead: () -> Unit = {}

    init {
        transports.forEach { queue.put(it) }
    }

    fun lease(timeoutMs: Long = 60_000): UsbTransport =
        queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
            ?: throw IOException("Timed out waiting for a free printer channel")

    fun release(transport: UsbTransport) {
        queue.put(transport)
    }

    fun discard(transport: UsbTransport) {
        try { transport.close() } catch (_: Exception) {}
        if (alive.decrementAndGet() == 0) onAllChannelsDead()
    }

    fun closeAll() {
        while (true) {
            val t = queue.poll() ?: break
            try { t.close() } catch (_: Exception) {}
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.relay.ChannelPoolTest"`
Expected: 3 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: IPP-USB channel pool with discard semantics"
```

---

### Task 6: Single-transaction HTTP relay

**Files:**
- Create: `SRC/relay/HttpRelay.kt`
- Test: `TST/relay/HttpRelayTest.kt`

- [ ] **Step 1: Write the failing tests**

`TST/relay/HttpRelayTest.kt`:
```kotlin
package dev.jaspreet.printserver.relay

import dev.jaspreet.printserver.http.HttpHead
import dev.jaspreet.printserver.usb.FakePrinterTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class HttpRelayTest {

    private val cannedResponse =
        "HTTP/1.1 200 OK\r\nContent-Type: application/ipp\r\nContent-Length: 4\r\n\r\nDONE"
            .toByteArray(Charsets.ISO_8859_1)

    @Test
    fun `forwards request with rewritten host and returns printer response`() {
        val printer = FakePrinterTransport { cannedResponse }
        val request = "POST /ipp/print HTTP/1.1\r\nHost: phone.lan:8631\r\nContent-Length: 5\r\n\r\nhello"
        val clientIn = ByteArrayInputStream(request.toByteArray(Charsets.ISO_8859_1))
        val clientOut = ByteArrayOutputStream()

        val head = HttpHead.parse(clientIn)!!
        HttpRelay.forward(head, clientIn, clientOut, printer)

        val sent = String(printer.lastRequest(), Charsets.ISO_8859_1)
        assertTrue("Host must be rewritten", sent.contains("Host: localhost\r\n"))
        assertTrue("Body must be forwarded", sent.endsWith("hello"))
        assertEquals(String(cannedResponse, Charsets.ISO_8859_1), clientOut.toString("ISO-8859-1"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.relay.HttpRelayTest"`
Expected: compilation FAILURE — `HttpRelay` not defined.

- [ ] **Step 3: Write the implementation**

`SRC/relay/HttpRelay.kt`:
```kotlin
package dev.jaspreet.printserver.relay

import dev.jaspreet.printserver.http.BodyCopier
import dev.jaspreet.printserver.http.HttpHead
import dev.jaspreet.printserver.usb.UsbTransport
import dev.jaspreet.printserver.usb.UsbTransportInputStream
import dev.jaspreet.printserver.usb.UsbTransportOutputStream
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

object HttpRelay {
    /**
     * Forwards one already-parsed HTTP request ([head] + remaining body on
     * [clientIn]) over [usb] and streams the printer's response to [clientOut].
     * The caller owns channel lease/release/discard.
     */
    fun forward(head: HttpHead, clientIn: InputStream, clientOut: OutputStream, usb: UsbTransport) {
        val usbOut = UsbTransportOutputStream(usb)
        val usbIn = BufferedInputStream(UsbTransportInputStream(usb))

        head.set("Host", "localhost")   // some printer firmware rejects unknown hosts
        usbOut.write(head.serialize())
        BodyCopier.copy(head, clientIn, usbOut)
        usbOut.flush()

        val respHead = HttpHead.parse(usbIn) ?: throw IOException("Printer closed channel without response")
        clientOut.write(respHead.serialize())
        BodyCopier.copy(respHead, usbIn, clientOut)
        clientOut.flush()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.relay.HttpRelayTest"`
Expected: 1 test PASS.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: single-transaction HTTP relay with Host rewrite"
```

---

### Task 7: IPP relay server (sockets + keep-alive + channel hygiene)

**Files:**
- Create: `SRC/relay/ActivityMonitor.kt`
- Create: `SRC/relay/IppRelayServer.kt`
- Test: `TST/relay/IppRelayServerTest.kt`

- [ ] **Step 1: Write the failing integration test**

`TST/relay/IppRelayServerTest.kt`:
```kotlin
package dev.jaspreet.printserver.relay

import dev.jaspreet.printserver.usb.FakePrinterTransport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class IppRelayServerTest {

    private var server: IppRelayServer? = null

    @After
    fun tearDown() { server?.stop() }

    private fun startServer(): Int {
        val printer = FakePrinterTransport { req ->
            // echo the request body length back
            val body = "len=${req.size}"
            ("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\n\r\n$body")
                .toByteArray(Charsets.ISO_8859_1)
        }
        val s = IppRelayServer(port = 0, pool = ChannelPool(listOf(printer)))
        s.start(bindAddress = null)
        server = s
        return s.actualPort
    }

    @Test
    fun `relays a POST end to end over real sockets`() {
        val port = startServer()
        val conn = URL("http://127.0.0.1:$port/ipp/print").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(3)
        conn.outputStream.use { it.write("abc".toByteArray()) }
        assertEquals(200, conn.responseCode)
        val body = conn.inputStream.readBytes().toString(Charsets.ISO_8859_1)
        assertEquals(true, body.startsWith("len="))
    }

    @Test
    fun `serves two sequential requests on keep-alive connections`() {
        val port = startServer()
        repeat(2) {
            val conn = URL("http://127.0.0.1:$port/ipp/print").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(1)
            conn.outputStream.use { it.write("x".toByteArray()) }
            assertEquals(200, conn.responseCode)
            conn.inputStream.readBytes()
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.relay.IppRelayServerTest"`
Expected: compilation FAILURE — `IppRelayServer`, `ActivityMonitor` not defined.

- [ ] **Step 3: Write the implementation**

`SRC/relay/ActivityMonitor.kt`:
```kotlin
package dev.jaspreet.printserver.relay

/** Job-activity hooks; the service maps these to a reference-counted wakelock. */
interface ActivityMonitor {
    fun begin()
    fun end()

    companion object {
        val NONE = object : ActivityMonitor {
            override fun begin() {}
            override fun end() {}
        }
    }
}
```

`SRC/relay/IppRelayServer.kt`:
```kotlin
package dev.jaspreet.printserver.relay

import dev.jaspreet.printserver.http.HttpHead
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors

/**
 * Accepts LAN HTTP connections and relays each transaction over a pooled
 * IPP-USB channel. Thread-per-connection: both socket and USB I/O block.
 */
class IppRelayServer(
    private val port: Int,
    private val pool: ChannelPool,
    private val monitor: ActivityMonitor = ActivityMonitor.NONE,
) {
    @Volatile private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()

    val actualPort: Int get() = serverSocket?.localPort ?: port

    fun start(bindAddress: InetAddress?) {
        val ss = ServerSocket(port, 50, bindAddress)
        serverSocket = ss
        executor.execute {
            while (!ss.isClosed) {
                val client = try { ss.accept() } catch (_: IOException) { break }
                executor.execute { handleClient(client) }
            }
        }
    }

    private fun handleClient(client: Socket) {
        client.soTimeout = 30_000
        client.use {
            val cin = BufferedInputStream(client.getInputStream())
            val cout = BufferedOutputStream(client.getOutputStream())
            while (true) {
                // Parse the head BEFORE leasing, so an idle keep-alive
                // connection never pins a printer channel.
                val head = try { HttpHead.parse(cin) ?: break } catch (_: SocketTimeoutException) { break } catch (_: IOException) { break }
                val channel = pool.lease()
                monitor.begin()
                try {
                    HttpRelay.forward(head, cin, cout, channel)
                    pool.release(channel)
                } catch (e: Exception) {
                    // Channel state unknown mid-transaction: never reuse it.
                    pool.discard(channel)
                    break
                } finally {
                    monitor.end()
                }
                if (head.get("Connection")?.equals("close", ignoreCase = true) == true) break
            }
        }
    }

    fun stop() {
        try { serverSocket?.close() } catch (_: IOException) {}
        executor.shutdownNow()
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.relay.IppRelayServerTest"`
Expected: 2 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: IPP relay server with keep-alive and channel hygiene"
```

---

### Task 8: Printer attribute query + TXT records (JIPP)

**Files:**
- Create: `SRC/ipp/PrinterInfo.kt`
- Create: `SRC/ipp/PrinterQuery.kt`
- Create: `SRC/ipp/TxtRecords.kt`
- Test: `TST/ipp/PrinterQueryTest.kt`
- Test: `TST/ipp/TxtRecordsTest.kt`

- [ ] **Step 1: Write the failing tests**

`TST/ipp/PrinterQueryTest.kt`:
```kotlin
package dev.jaspreet.printserver.ipp

import com.hp.jipp.encoding.AttributeGroup.Companion.groupOf
import com.hp.jipp.encoding.IppOutputStream
import com.hp.jipp.encoding.IppPacket
import com.hp.jipp.encoding.Tag
import com.hp.jipp.model.Status
import com.hp.jipp.model.Types
import dev.jaspreet.printserver.relay.ChannelPool
import dev.jaspreet.printserver.usb.FakePrinterTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.URI

class PrinterQueryTest {

    private fun ippResponseBytes(): ByteArray {
        val packet = IppPacket(
            Status.successfulOk, 1,
            groupOf(
                Tag.operationAttributes,
                Types.attributesCharset.of("utf-8"),
                Types.attributesNaturalLanguage.of("en"),
            ),
            groupOf(
                Tag.printerAttributes,
                Types.printerMakeAndModel.of("Test Laser 9000"),
                Types.documentFormatSupported.of("application/pdf", "image/pwg-raster"),
                Types.colorSupported.of(true),
                Types.printerUuid.of(URI.create("urn:uuid:11111111-2222-3333-4444-555555555555")),
            ),
        )
        val ipp = ByteArrayOutputStream()
        IppOutputStream(ipp).write(packet)
        val body = ipp.toByteArray()
        val head = "HTTP/1.1 200 OK\r\nContent-Type: application/ipp\r\nContent-Length: ${body.size}\r\n\r\n"
        return head.toByteArray(Charsets.ISO_8859_1) + body
    }

    @Test
    fun `queries and parses printer attributes over a channel`() {
        val printer = FakePrinterTransport { ippResponseBytes() }
        val info = PrinterQuery.getAttributes(ChannelPool(listOf(printer)))
        assertEquals("Test Laser 9000", info.makeAndModel)
        assertEquals(listOf("application/pdf", "image/pwg-raster"), info.formats)
        assertTrue(info.color)
        assertEquals("11111111-2222-3333-4444-555555555555", info.uuid)
        // and the request we sent was an HTTP POST carrying IPP
        val sent = String(printer.lastRequest(), Charsets.ISO_8859_1)
        assertTrue(sent.startsWith("POST /ipp/print HTTP/1.1\r\n"))
        assertTrue(sent.contains("Content-Type: application/ipp"))
    }
}
```

`TST/ipp/TxtRecordsTest.kt`:
```kotlin
package dev.jaspreet.printserver.ipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TxtRecordsTest {

    @Test
    fun `builds ipp everywhere txt records`() {
        val info = PrinterInfo(
            makeAndModel = "Test Laser 9000",
            formats = listOf("application/pdf", "image/pwg-raster"),
            color = true,
            uuid = "11111111-2222-3333-4444-555555555555",
            urf = listOf("V1.4", "W8", "SRGB24"),
        )
        val txt = TxtRecords.forIpp(info)
        assertEquals("1", txt["txtvers"])
        assertEquals("ipp/print", txt["rp"])
        assertEquals("application/pdf,image/pwg-raster", txt["pdl"])
        assertEquals("T", txt["color"])
        assertEquals("11111111-2222-3333-4444-555555555555", txt["UUID"])
        assertEquals("V1.4,W8,SRGB24", txt["URF"])
        assertEquals("Test Laser 9000", txt["ty"])
        assertEquals("1", txt["qtotal"])
    }

    @Test
    fun `omits URF when printer reports none`() {
        val info = PrinterInfo("X", listOf("application/pdf"), false, null, emptyList())
        val txt = TxtRecords.forIpp(info)
        assertFalse(txt.containsKey("URF"))
        assertFalse(txt.containsKey("UUID"))
        assertEquals("F", txt["color"])
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.ipp.*"`
Expected: compilation FAILURE — `PrinterInfo`, `PrinterQuery`, `TxtRecords` not defined.

- [ ] **Step 3: Write the implementation**

`SRC/ipp/PrinterInfo.kt`:
```kotlin
package dev.jaspreet.printserver.ipp

data class PrinterInfo(
    val makeAndModel: String,
    val formats: List<String>,
    val color: Boolean,
    val uuid: String?,          // bare UUID, no urn:uuid: prefix
    val urf: List<String>,      // AirPrint URF capability tokens, may be empty
)
```

`SRC/ipp/PrinterQuery.kt`:
```kotlin
package dev.jaspreet.printserver.ipp

import com.hp.jipp.encoding.AttributeGroup.Companion.groupOf
import com.hp.jipp.encoding.IppInputStream
import com.hp.jipp.encoding.IppOutputStream
import com.hp.jipp.encoding.IppPacket
import com.hp.jipp.encoding.Tag
import com.hp.jipp.model.Operation
import com.hp.jipp.model.Types
import dev.jaspreet.printserver.http.BodyCopier
import dev.jaspreet.printserver.http.HttpHead
import dev.jaspreet.printserver.relay.ChannelPool
import dev.jaspreet.printserver.usb.UsbTransportInputStream
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI

/** One-time Get-Printer-Attributes over an IPP-USB channel; feeds mDNS TXT and the UI. */
object PrinterQuery {

    fun getAttributes(pool: ChannelPool): PrinterInfo {
        val ippBody = buildRequestBytes()
        val http = ("POST /ipp/print HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Type: application/ipp\r\n" +
                "Content-Length: ${ippBody.size}\r\n\r\n")
            .toByteArray(Charsets.ISO_8859_1)

        val channel = pool.lease()
        try {
            channel.write(http, 0, http.size)
            channel.write(ippBody, 0, ippBody.size)
            val input = BufferedInputStream(UsbTransportInputStream(channel))
            val head = HttpHead.parse(input) ?: throw IOException("No response to attribute query")
            val body = ByteArrayOutputStream()
            BodyCopier.copy(head, input, body)
            pool.release(channel)
            return parse(IppInputStream(ByteArrayInputStream(body.toByteArray())).readPacket())
        } catch (e: Exception) {
            pool.discard(channel)
            throw e
        }
    }

    private fun buildRequestBytes(): ByteArray {
        val packet = IppPacket(
            Operation.getPrinterAttributes, 1,
            groupOf(
                Tag.operationAttributes,
                Types.attributesCharset.of("utf-8"),
                Types.attributesNaturalLanguage.of("en"),
                Types.printerUri.of(URI.create("ipp://localhost/ipp/print")),
                Types.requestedAttributes.of(
                    "printer-make-and-model",
                    "document-format-supported",
                    "color-supported",
                    "printer-uuid",
                    "urf-supported",
                ),
            ),
        )
        val out = ByteArrayOutputStream()
        IppOutputStream(out).write(packet)
        return out.toByteArray()
    }

    private fun parse(packet: IppPacket): PrinterInfo {
        val group = packet[Tag.printerAttributes]
            ?: throw IOException("Response has no printer-attributes group")
        val make = group.getValue(Types.printerMakeAndModel)?.value ?: "USB Printer"
        val formats = group.getValues(Types.documentFormatSupported)
        val color = group.getValue(Types.colorSupported) ?: false
        val uuid = group.getValue(Types.printerUuid)?.toString()?.removePrefix("urn:uuid:")
        // urf-supported is not in JIPP's typed model on all versions; read it raw.
        val urf = group["urf-supported"]?.strings() ?: emptyList()
        return PrinterInfo(make, formats, color, uuid, urf)
    }
}
```

`SRC/ipp/TxtRecords.kt`:
```kotlin
package dev.jaspreet.printserver.ipp

/** DNS-SD TXT records for the _ipp._tcp advertisement (IPP Everywhere + AirPrint keys). */
object TxtRecords {
    fun forIpp(info: PrinterInfo): Map<String, String> = buildMap {
        put("txtvers", "1")
        put("qtotal", "1")
        put("rp", "ipp/print")
        put("ty", info.makeAndModel)
        put("pdl", info.formats.joinToString(","))
        put("color", if (info.color) "T" else "F")
        info.uuid?.let { put("UUID", it) }
        if (info.urf.isNotEmpty()) put("URF", info.urf.joinToString(","))
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.ipp.*"`
Expected: 3 tests PASS.

Note: JIPP's exact accessor names (`getValue`/`getValues`/`get`/`strings`) vary slightly between 0.6.x and 0.7.x. If compilation fails here, check the installed version's `AttributeGroup` API (https://github.com/HPInc/jipp) and adjust `parse()` only — the tests define the required behavior and must not be weakened.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: printer attribute query and DNS-SD TXT record builder"
```

---

### Task 9: Android USB layer

Android-API-dependent code; no JVM unit tests. Verified by compilation now, hardware smoke test in Task 14. Keep every decision that CAN be pure (descriptor matching) in a testable helper.

**Files:**
- Create: `SRC/usb/IppUsb.kt`
- Create: `SRC/usb/AndroidUsbTransport.kt`
- Create: `SRC/usb/UsbPrinterManager.kt`
- Create: `app/src/main/res/xml/device_filter.xml`
- Modify: `app/src/main/AndroidManifest.xml` (USB attach intent filter on MainActivity)
- Test: `TST/usb/IppUsbTest.kt`

- [ ] **Step 1: Write the failing test for descriptor matching**

`TST/usb/IppUsbTest.kt`:
```kotlin
package dev.jaspreet.printserver.usb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IppUsbTest {
    @Test
    fun `detects ipp-usb interface descriptor 7-1-4`() {
        assertTrue(IppUsb.isIppUsb(7, 1, 4))
        assertFalse(IppUsb.isIppUsb(7, 1, 2))   // classic bidirectional printer
        assertFalse(IppUsb.isIppUsb(8, 1, 4))   // mass storage
    }

    @Test
    fun `detects legacy printer interface`() {
        assertTrue(IppUsb.isLegacyPrinter(7, 1, 1))
        assertTrue(IppUsb.isLegacyPrinter(7, 1, 2))
        assertFalse(IppUsb.isLegacyPrinter(7, 1, 4))
        assertFalse(IppUsb.isLegacyPrinter(3, 0, 0))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.usb.IppUsbTest"`
Expected: compilation FAILURE — `IppUsb` not defined.

- [ ] **Step 3: Write IppUsb helper**

`SRC/usb/IppUsb.kt`:
```kotlin
package dev.jaspreet.printserver.usb

object IppUsb {
    const val CLASS_PRINTER = 7

    /** IPP-USB per spec: interface class 7 (printer), subclass 1, protocol 4. */
    fun isIppUsb(interfaceClass: Int, subclass: Int, protocol: Int): Boolean =
        interfaceClass == CLASS_PRINTER && subclass == 1 && protocol == 4

    /** Classic USB printer-class interface (unidirectional=1 / bidirectional=2 / 1284.4=3). */
    fun isLegacyPrinter(interfaceClass: Int, subclass: Int, protocol: Int): Boolean =
        interfaceClass == CLASS_PRINTER && !isIppUsb(interfaceClass, subclass, protocol)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.usb.IppUsbTest"`
Expected: 2 tests PASS.

- [ ] **Step 5: Write the Android USB transport and manager**

`SRC/usb/AndroidUsbTransport.kt`:
```kotlin
package dev.jaspreet.printserver.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import java.io.IOException

class AndroidUsbTransport(
    private val connection: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val outEndpoint: UsbEndpoint,
    private val inEndpoint: UsbEndpoint,
) : UsbTransport {

    override fun write(data: ByteArray, offset: Int, length: Int) {
        var off = offset
        var left = length
        while (left > 0) {
            val chunk = minOf(left, 16384)
            val n = connection.bulkTransfer(outEndpoint, data, off, chunk, WRITE_TIMEOUT_MS)
            if (n < 0) throw IOException("USB bulk write failed at offset $off")
            off += n
            left -= n
        }
    }

    override fun read(buffer: ByteArray): Int {
        val n = connection.bulkTransfer(inEndpoint, buffer, buffer.size, READ_TIMEOUT_MS)
        if (n < 0) throw IOException("USB bulk read failed or timed out")
        return n
    }

    override fun close() {
        try { connection.releaseInterface(iface) } catch (_: Exception) {}
    }

    private companion object {
        const val WRITE_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 60_000   // responses can lag while the printer chews a job
    }
}
```

`SRC/usb/UsbPrinterManager.kt`:
```kotlin
package dev.jaspreet.printserver.usb

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.IOException

class UsbPrinterManager(private val context: Context) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    fun findPrinter(): UsbDevice? = usbManager.deviceList.values.firstOrNull { device ->
        device.interfaces().any { it.interfaceClass == IppUsb.CLASS_PRINTER }
    }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    fun requestPermission(device: UsbDevice) {
        val intent = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE,
        )
        usbManager.requestPermission(device, intent)
    }

    /** Opens every IPP-USB interface on the device as an exclusive channel. Empty list = not IPP-USB. */
    fun openIppTransports(device: UsbDevice): List<UsbTransport> =
        device.interfaces()
            .filter { IppUsb.isIppUsb(it.interfaceClass, it.interfaceSubclass, it.interfaceProtocol) }
            .mapNotNull { openInterface(device, it) }

    /** Opens the first classic printer-class interface (for the raw 9100 path). */
    fun openLegacyTransport(device: UsbDevice): UsbTransport? =
        device.interfaces()
            .firstOrNull { IppUsb.isLegacyPrinter(it.interfaceClass, it.interfaceSubclass, it.interfaceProtocol) }
            ?.let { openInterface(device, it) }

    private fun openInterface(device: UsbDevice, iface: UsbInterface): UsbTransport? {
        var outEp: UsbEndpoint? = null
        var inEp: UsbEndpoint? = null
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_OUT && outEp == null) outEp = ep
            if (ep.direction == UsbConstants.USB_DIR_IN && inEp == null) inEp = ep
        }
        if (outEp == null || inEp == null) return null
        val connection = usbManager.openDevice(device) ?: throw IOException("openDevice failed — permission?")
        if (!connection.claimInterface(iface, true)) {
            connection.close()
            throw IOException("claimInterface failed for interface ${iface.id}")
        }
        return AndroidUsbTransport(connection, iface, outEp, inEp)
    }

    private fun UsbDevice.interfaces(): List<UsbInterface> =
        (0 until interfaceCount).map { getInterface(it) }

    companion object {
        const val ACTION_USB_PERMISSION = "dev.jaspreet.printserver.USB_PERMISSION"
    }
}
```

- [ ] **Step 6: Add USB attach intent filter and device filter resource**

`app/src/main/res/xml/device_filter.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- Any USB printer-class device (matches device or interface class 7) -->
    <usb-device class="7" subclass="-1" protocol="-1" />
</resources>
```

In `AndroidManifest.xml`, inside the `MainActivity` `<activity>` element, add alongside the existing launcher intent filter:
```xml
<intent-filter>
    <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />
</intent-filter>
<meta-data
    android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"
    android:resource="@xml/device_filter" />
```
(Attach-intent launch grants the app durable permission for that device — no dialog on replug.)

- [ ] **Step 7: Verify the whole app still compiles**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests PASS.

- [ ] **Step 8: Commit**

```bash
git add -A && git commit -m "feat: Android USB printer manager and IPP-USB transport"
```

---

### Task 10: Discovery advertiser (NsdManager behind interface)

**Files:**
- Create: `SRC/discovery/DiscoveryAdvertiser.kt`
- Create: `SRC/discovery/NsdAdvertiser.kt`

No JVM test (NsdManager is Android-only); verified by build + Task 14 `dns-sd` check. The interface exists precisely so a future NDK mDNSResponder implementation can slot in.

- [ ] **Step 1: Write the interface and implementation**

`SRC/discovery/DiscoveryAdvertiser.kt`:
```kotlin
package dev.jaspreet.printserver.discovery

interface DiscoveryAdvertiser {
    fun advertiseIpp(name: String, port: Int, txt: Map<String, String>)
    fun advertiseRaw(name: String, port: Int)
    /** Withdraw all advertisements (network change, printer unplug, shutdown). */
    fun stopAll()
}
```

`SRC/discovery/NsdAdvertiser.kt`:
```kotlin
package dev.jaspreet.printserver.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

class NsdAdvertiser(context: Context) : DiscoveryAdvertiser {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val registrations = mutableListOf<NsdManager.RegistrationListener>()

    override fun advertiseIpp(name: String, port: Int, txt: Map<String, String>) {
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = "_ipp._tcp"
            setPort(port)
            txt.forEach { (k, v) -> setAttribute(k, v) }
        }
        register(info)
    }

    override fun advertiseRaw(name: String, port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = "_pdl-datastream._tcp"
            setPort(port)
        }
        register(info)
    }

    private fun register(info: NsdServiceInfo) {
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) = Log.i(TAG, "Registered ${i.serviceName} ${i.serviceType}")
            override fun onRegistrationFailed(i: NsdServiceInfo, error: Int) = Log.e(TAG, "Registration failed: $error")
            override fun onServiceUnregistered(i: NsdServiceInfo) = Log.i(TAG, "Unregistered ${i.serviceName}")
            override fun onUnregistrationFailed(i: NsdServiceInfo, error: Int) = Log.e(TAG, "Unregistration failed: $error")
        }
        registrations += listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    override fun stopAll() {
        registrations.forEach { runCatching { nsd.unregisterService(it) } }
        registrations.clear()
    }

    private companion object { const val TAG = "NsdAdvertiser" }
}
```

- [ ] **Step 2: Verify build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "feat: mDNS advertiser behind swappable discovery interface"
```

---

### Task 11: Raw port-9100 relay (legacy fallback)

**Files:**
- Create: `SRC/relay/Raw9100Relay.kt`
- Test: `TST/relay/Raw9100RelayTest.kt`

- [ ] **Step 1: Write the failing test**

`TST/relay/Raw9100RelayTest.kt`:
```kotlin
package dev.jaspreet.printserver.relay

import dev.jaspreet.printserver.usb.FakePrinterTransport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.Socket

class Raw9100RelayTest {

    private var relay: Raw9100Relay? = null

    @After
    fun tearDown() { relay?.stop() }

    @Test
    fun `pipes client bytes verbatim to the printer`() {
        val printer = FakePrinterTransport { ByteArray(0) }
        val r = Raw9100Relay(port = 0) { printer }
        r.start(bindAddress = null)
        relay = r

        Socket("127.0.0.1", r.actualPort).use { socket ->
            socket.getOutputStream().write("RAW PCL BYTES".toByteArray())
            socket.shutdownOutput()
            // wait for the relay to drain the socket
            Thread.sleep(300)
        }
        assertEquals("RAW PCL BYTES", String(printer.lastRequest()))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.relay.Raw9100RelayTest"`
Expected: compilation FAILURE — `Raw9100Relay` not defined.

- [ ] **Step 3: Write the implementation**

`SRC/relay/Raw9100Relay.kt`:
```kotlin
package dev.jaspreet.printserver.relay

import dev.jaspreet.printserver.usb.UsbTransport
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * JetDirect/AppSocket fallback for non-IPP-USB printers: one client at a
 * time, bytes piped verbatim to the printer's bulk OUT. The client must
 * have the printer's driver installed — this path does no translation.
 */
class Raw9100Relay(
    private val port: Int,
    private val transportProvider: () -> UsbTransport,
) {
    @Volatile private var serverSocket: ServerSocket? = null
    private val executor = Executors.newSingleThreadExecutor()

    val actualPort: Int get() = serverSocket?.localPort ?: port

    fun start(bindAddress: InetAddress?) {
        val ss = ServerSocket(port, 1, bindAddress)
        serverSocket = ss
        executor.execute {
            while (!ss.isClosed) {
                val client = try { ss.accept() } catch (_: IOException) { break }
                client.use { handle(it) }
            }
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = 60_000
        val transport = transportProvider()
        val buf = ByteArray(65536)
        val input = client.getInputStream()
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                transport.write(buf, 0, n)
            }
        } catch (_: IOException) {
            // client gone or printer stalled; drop the connection
        }
    }

    fun stop() {
        try { serverSocket?.close() } catch (_: IOException) {}
        executor.shutdownNow()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.relay.Raw9100RelayTest"`
Expected: 1 test PASS.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: raw port-9100 relay for non-IPP-USB printers"
```

---

### Task 12: Foreground service wiring

**Files:**
- Create: `SRC/service/ServerState.kt`
- Create: `SRC/service/WifiAddress.kt`
- Create: `SRC/service/ServerService.kt`
- Modify: `app/src/main/AndroidManifest.xml` (service declaration)

No JVM test — this is lifecycle glue over components already tested above. Verified by build + Task 14.

- [ ] **Step 1: Write state holder and Wi-Fi address helper**

`SRC/service/ServerState.kt`:
```kotlin
package dev.jaspreet.printserver.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ServerStatus(
    val running: Boolean = false,
    val printerName: String? = null,
    val ippSupported: Boolean = true,
    val ip: String? = null,
    val port: Int? = null,
    val message: String = "Idle",
)

object ServerState {
    private val _status = MutableStateFlow(ServerStatus())
    val status: StateFlow<ServerStatus> = _status.asStateFlow()
    fun update(transform: (ServerStatus) -> ServerStatus) { _status.value = transform(_status.value) }
}
```

`SRC/service/WifiAddress.kt`:
```kotlin
package dev.jaspreet.printserver.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

object WifiAddress {
    /** IPv4 address of the Wi-Fi interface, or null when Wi-Fi is down. Servers bind here — never 0.0.0.0. */
    fun get(context: Context): Inet4Address? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        val wifi = cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: return null
        return cm.getLinkProperties(wifi)?.linkAddresses
            ?.map { it.address }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull()
    }
}
```

- [ ] **Step 2: Write the service**

`SRC/service/ServerService.kt`:
```kotlin
package dev.jaspreet.printserver.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.jaspreet.printserver.MainActivity
import dev.jaspreet.printserver.R
import dev.jaspreet.printserver.discovery.DiscoveryAdvertiser
import dev.jaspreet.printserver.discovery.NsdAdvertiser
import dev.jaspreet.printserver.ipp.PrinterQuery
import dev.jaspreet.printserver.ipp.TxtRecords
import dev.jaspreet.printserver.relay.ActivityMonitor
import dev.jaspreet.printserver.relay.ChannelPool
import dev.jaspreet.printserver.relay.IppRelayServer
import dev.jaspreet.printserver.relay.Raw9100Relay
import dev.jaspreet.printserver.usb.UsbPrinterManager
import dev.jaspreet.printserver.usb.UsbTransport
import kotlin.concurrent.thread

class ServerService : Service() {

    private var pool: ChannelPool? = null
    private var ippServer: IppRelayServer? = null
    private var rawRelay: Raw9100Relay? = null
    private var advertiser: DiscoveryAdvertiser? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                update { it.copy(running = false, message = "Printer disconnected") }
                stopSelf()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "printserver:jobs")
            .apply { setReferenceCounted(true) }
        registerReceiver(detachReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Starting print server…"))
        thread(name = "pipeline-start") { startPipeline() }
        return START_STICKY
    }

    private fun startPipeline() {
        try {
            val usb = UsbPrinterManager(this)
            val device = usb.findPrinter()
                ?: return fail("No USB printer connected")
            if (!usb.hasPermission(device)) {
                usb.requestPermission(device)
                return fail("Grant the USB permission dialog, then toggle the server on again")
            }
            val bindAddr = WifiAddress.get(this)
                ?: return fail("Wi-Fi is not connected")
            val name = device.productName ?: "USB Printer"

            val ippTransports = usb.openIppTransports(device)
            if (ippTransports.isNotEmpty()) {
                startIppPipeline(name, ippTransports, bindAddr)
            } else {
                startLegacyPipeline(name, usb, device, bindAddr)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Pipeline start failed", e)
            fail(e.message ?: "Unknown startup error")
        }
    }

    private fun startIppPipeline(
        name: String,
        transports: List<UsbTransport>,
        bindAddr: java.net.Inet4Address,
    ) {
        val channelPool = ChannelPool(transports).also { pool = it }
        channelPool.onAllChannelsDead = {
            update { it.copy(running = false, message = "Printer stopped responding — replug it") }
            stopSelf()
        }
        val info = PrinterQuery.getAttributes(channelPool)
        val monitor = object : ActivityMonitor {
            override fun begin() { wakeLock?.acquire(10 * 60 * 1000L) }
            override fun end() { if (wakeLock?.isHeld == true) wakeLock?.release() }
        }
        val server = IppRelayServer(IPP_PORT, channelPool, monitor).also { ippServer = it }
        server.start(bindAddr)
        advertiser = NsdAdvertiser(this).also {
            it.advertiseIpp(info.makeAndModel, IPP_PORT, TxtRecords.forIpp(info))
        }
        update {
            it.copy(running = true, printerName = info.makeAndModel, ippSupported = true,
                ip = bindAddr.hostAddress, port = IPP_PORT, message = "Serving ${info.makeAndModel}")
        }
        notify("Serving ${info.makeAndModel} at ${bindAddr.hostAddress}:$IPP_PORT")
    }

    private fun startLegacyPipeline(
        name: String,
        usb: UsbPrinterManager,
        device: android.hardware.usb.UsbDevice,
        bindAddr: java.net.Inet4Address,
    ) {
        val transport = usb.openLegacyTransport(device)
            ?: return fail("Printer has no usable USB interface")
        val relay = Raw9100Relay(RAW_PORT) { transport }.also { rawRelay = it }
        relay.start(bindAddr)
        advertiser = NsdAdvertiser(this).also { it.advertiseRaw(name, RAW_PORT) }
        update {
            it.copy(running = true, printerName = name, ippSupported = false,
                ip = bindAddr.hostAddress, port = RAW_PORT,
                message = "$name lacks IPP-USB. Driverless printing unavailable; raw port 9100 active for clients with the vendor driver.")
        }
        notify("$name on raw port $RAW_PORT (no driverless support)")
    }

    private fun fail(message: String) {
        update { it.copy(running = false, message = message) }
        notify(message)
        stopSelf()
    }

    private fun stopPipeline() {
        advertiser?.stopAll(); advertiser = null
        ippServer?.stop(); ippServer = null
        rawRelay?.stop(); rawRelay = null
        pool?.closeAll(); pool = null
    }

    override fun onDestroy() {
        stopPipeline()
        runCatching { unregisterReceiver(detachReceiver) }
        while (wakeLock?.isHeld == true) wakeLock?.release()
        update { it.copy(running = false) }
        super.onDestroy()
    }

    private fun update(transform: (ServerStatus) -> ServerStatus) = ServerState.update(transform)

    private fun buildNotification(text: String): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Print server", NotificationManager.IMPORTANCE_LOW)
        )
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(tap)
            .setOngoing(true)
            .build()
    }

    private fun notify(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        private const val TAG = "ServerService"
        private const val CHANNEL_ID = "printserver"
        private const val NOTIFICATION_ID = 1
        const val IPP_PORT = 8631
        const val RAW_PORT = 9100
    }
}
```

- [ ] **Step 3: Declare the service in the manifest**

Inside `<application>` in `AndroidManifest.xml`:
```xml
<service
    android:name=".service.ServerService"
    android:exported="false"
    android:foregroundServiceType="connectedDevice" />
```

- [ ] **Step 4: Verify build**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all existing tests PASS.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: foreground service wiring pipeline, wakelock, detach handling"
```

---

### Task 13: Status UI

**Files:**
- Modify: `SRC/MainActivity.kt`
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Expand the layout**

`app/src/main/res/layout/activity_main.xml` (replace file):
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="16dp">

    <TextView
        android:id="@+id/printerText"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:textSize="20sp"
        android:textStyle="bold"
        android:text="@string/status_idle" />

    <TextView
        android:id="@+id/statusText"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:textSize="16sp" />

    <TextView
        android:id="@+id/addressText"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:textSize="16sp"
        android:fontFamily="monospace" />

    <TextView
        android:id="@+id/legacyBanner"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="16dp"
        android:padding="12dp"
        android:background="#33FF9800"
        android:visibility="gone"
        android:text="@string/legacy_banner" />

    <Button
        android:id="@+id/toggleButton"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="24dp"
        android:text="@string/start_server" />

    <Button
        android:id="@+id/batteryButton"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:text="@string/battery_exemption" />
</LinearLayout>
```

`app/src/main/res/values/strings.xml` (replace file):
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">PrintServer</string>
    <string name="status_idle">Idle — plug in a USB printer</string>
    <string name="start_server">Start server</string>
    <string name="stop_server">Stop server</string>
    <string name="battery_exemption">Disable battery optimization</string>
    <string name="legacy_banner">This printer does not support IPP-USB, so driverless printing is unavailable. Raw port 9100 is active for computers that have the printer\'s driver installed.</string>
</resources>
```

- [ ] **Step 2: Wire the activity to ServerState**

`SRC/MainActivity.kt` (replace file):
```kotlin
package dev.jaspreet.printserver

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dev.jaspreet.printserver.service.ServerService
import dev.jaspreet.printserver.service.ServerState
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val printerText = findViewById<TextView>(R.id.printerText)
        val statusText = findViewById<TextView>(R.id.statusText)
        val addressText = findViewById<TextView>(R.id.addressText)
        val legacyBanner = findViewById<TextView>(R.id.legacyBanner)
        val toggleButton = findViewById<Button>(R.id.toggleButton)
        val batteryButton = findViewById<Button>(R.id.batteryButton)

        toggleButton.setOnClickListener {
            val running = ServerState.status.value.running
            val intent = Intent(this, ServerService::class.java)
            if (running) stopService(intent) else startForegroundService(intent)
        }

        batteryButton.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }

        lifecycleScope.launch {
            ServerState.status.collect { s ->
                printerText.text = s.printerName ?: getString(R.string.status_idle)
                statusText.text = s.message
                addressText.text = if (s.running && s.ip != null) "http://${s.ip}:${s.port}" else ""
                legacyBanner.visibility =
                    if (s.running && !s.ippSupported) View.VISIBLE else View.GONE
                toggleButton.text =
                    getString(if (s.running) R.string.stop_server else R.string.start_server)
            }
        }

        // Launched by USB attach intent -> start serving immediately.
        if (intent?.action == "android.hardware.usb.action.USB_DEVICE_ATTACHED") {
            startForegroundService(Intent(this, ServerService::class.java))
        }
    }
}
```

- [ ] **Step 3: Verify build**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests PASS.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat: status UI with server toggle and battery exemption"
```

---

### Task 14: Hardware smoke test checklist

**Files:**
- Create: `docs/superpowers/testing/hardware-smoke-checklist.md`

- [ ] **Step 1: Write the checklist document**

`docs/superpowers/testing/hardware-smoke-checklist.md`:
```markdown
# Hardware Smoke Test Checklist

Prereqs: Android phone (API 26+, USB host support), USB OTG adapter, a
post-2013 USB printer, phone and test clients on the same Wi-Fi network.
Install: `./gradlew :app:installDebug`.

## Setup
- [ ] Plug printer into phone via OTG. App launches (attach intent) or open it manually.
- [ ] Tap Start server. Grant USB permission if prompted.
- [ ] UI shows printer model + `http://<phone-ip>:8631`.
- [ ] Tap "Disable battery optimization" and accept.

## Discovery
- [ ] macOS: `dns-sd -B _ipp._tcp` lists the printer within ~5 s.
- [ ] macOS: `dns-sd -L "<name>" _ipp._tcp` shows TXT: rp=ipp/print, pdl=..., UUID=...

## Print paths (one page each; verify paper output)
- [ ] macOS: System Settings → Printers → printer appears via Bonjour → print a PDF page.
- [ ] Windows 11: Settings → Bluetooth & devices → Printers → Add device → appears → print test page.
- [ ] iPhone: Share → Print from Safari → printer appears (requires URF in TXT) → print.
- [ ] Linux: `ipptool -tv ipp://<phone-ip>:8631/ipp/print get-printer-attributes.test` passes,
      then `lp -h <phone-ip>:8631 -d ipp/print <file.pdf>` OR add via CUPS "everywhere" driver.
- [ ] Host phone itself: open a PDF → Print → Default Print Service lists the printer → print.

## Resilience
- [ ] Print two jobs from two machines at the same time — both complete.
- [ ] Unplug USB mid-idle: UI shows "Printer disconnected", mDNS entry disappears.
- [ ] Replug: app auto-starts, printer rediscoverable, printing works.
- [ ] Toggle Wi-Fi off/on on the phone: after reconnect, restart server, clients rediscover.
- [ ] Screen off 10 minutes, then print from a laptop — job still goes through.
- [ ] Cancel a job from the client mid-transfer; next job still prints (channel hygiene).

## Legacy path (only if a non-IPP-USB printer is available)
- [ ] UI shows the "no driverless support" banner.
- [ ] From a PC with the vendor driver installed, add a raw TCP/IP printer at
      `<phone-ip>:9100` and print a page.
```

- [ ] **Step 2: Run the checklist with real hardware**

Work through every unchecked box with the physical printer. Record failures as issues; do not check a box without seeing the physical result.

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "docs: hardware smoke test checklist"
```

---

## Self-review notes

- **Spec coverage:** USB detection/permission (Task 9), channel pool (5), HTTP relay + Host rewrite (3,4,6,7), attribute query + TXT (8), mDNS behind interface (10), raw 9100 (11), foreground service + wakelock + detach + Wi-Fi-only binding (12), UI + banner + battery prompt (13), smoke tests incl. AirPrint/Default Print Service (14). Network-change mDNS re-registration is handled minimally in v1: server startup binds to the current Wi-Fi address and the smoke checklist covers the Wi-Fi-bounce path via manual restart; automatic re-registration is deferred (spec lists it as error handling — acceptable v1 simplification, noted here deliberately).
- **Known API risk:** JIPP accessor names in Task 8 `parse()` may need adjustment to the installed version; tests pin the behavior.
- **Type consistency check:** `UsbTransport.write(data, offset, length)` / `read(buffer): Int` used consistently by streams (Task 2), pool (5), relay (6), query (8), 9100 relay (11), Android impl (9). `ActivityMonitor.begin/end` defined Task 7, consumed Task 12. `PrinterInfo` fields defined Task 8, consumed Tasks 8/12.
