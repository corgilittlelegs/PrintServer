# Print Activity Feed — Design

## Purpose

Surface, in the existing main-screen UI, a feed of print jobs sent to the app while it's actively sharing — what was printed, by whom (client IP), when, and whether it succeeded. Currently the app has no visibility into print activity at all; a user watching the screen while sharing has no confirmation a job was received, let alone how it went.

## Scope

- Session-only: activity is in-memory, cleared when the server stops (matches how `PrinterInfo` fields are already cleared on stop — see `docs/superpowers/plans/2026-07-17-printer-info-ui.md`). No persistence layer, no database.
- Covers both tiers:
  - **Tier 2** (native rendering): rich per-job detail, since `LocalIppServer`/`JobQueue` already model the full job lifecycle.
  - **Tier 1** (IPP-USB relay): coarse per-request entries. The relay's core design principle — pure byte streaming, never buffering or parsing a full document — is preserved; only the first 4 bytes of an IPP request (version + operation-id) are peeked to classify the operation.
- UI: one new card on the existing main screen, not a separate screen.

## Data model

New file `app/src/main/java/dev/jaspreet/printserver/activity/ActivityLog.kt`:

```kotlin
enum class ActivityStatus { PRINTING, PRINTED, FAILED }

data class ActivityEntry(
    val id: Int,
    val tier: Int,                     // 1 or 2
    val name: String,                  // Tier 2: client-sent job name; Tier 1: "Print request"
    val status: ActivityStatus,
    val startedAt: Long,
    val completedAt: Long? = null,
    val clientAddress: String? = null,
    val sizeBytes: Long? = null,
    val format: String? = null,
    val failureReason: String? = null, // Tier 2: JobState.stateReason; Tier 1: exception class/message
)

object ActivityLog {
    private val _entries = MutableStateFlow<List<ActivityEntry>>(emptyList())
    val entries: StateFlow<List<ActivityEntry>> = _entries.asStateFlow()

    fun record(entry: ActivityEntry) { ... }   // prepend, cap at MAX_ENTRIES
    fun update(id: Int, transform: (ActivityEntry) -> ActivityEntry) { ... }
    fun clear() { ... }
}
```

Mirrors the existing `ServerState` singleton pattern (`service/ServerState.kt`) — a plain object with a `MutableStateFlow`, no DI framework in this codebase.

Cap: `MAX_ENTRIES = 200`, oldest dropped first. This is a memory safety bound, not a user-facing limit — 200 print jobs in one sharing session is far beyond realistic usage, so in practice the list is "unlimited" as intended.

`ServerService.stopServer()` (or wherever `ServerState.update { ServerStatus() }` currently resets state) also calls `ActivityLog.clear()`.

## Tier 2 wiring

`JobQueue` currently exposes only `onJobFinished: (PrintJob) -> Unit`, called once at the end of `process()`. Replace/extend with `onJobStateChanged: (PrintJob) -> Unit`, invoked at each transition:
- `submit()` / `reserve()` — job created (state PENDING)
- `enqueue()` — reserved job now queued
- start of `process()` — state set to PROCESSING
- end of `process()` — COMPLETED / ABORTED
- `cancel()` — CANCELED

`ServerService` supplies the callback when constructing `JobQueue`, translating `JobState` → `ActivityStatus`:
- PENDING/PROCESSING → PRINTING
- COMPLETED → PRINTED
- ABORTED/CANCELED → FAILED (`failureReason` = `job.stateReason`)

It calls `ActivityLog.record(...)` on first sight of a job id, `ActivityLog.update(id) { ... }` on subsequent transitions.

**Client IP**: not currently captured anywhere in the Tier 2 path. `LocalIppServer.handleClient(client: Socket)` has `client.inetAddress`; thread it as a parameter into `JobQueue.submit()` / `reserve()`, stored on `PrintJob.clientAddress`, so `ServerService`'s callback can read it off the job.

**Size**: `job.spoolFile.length()` once the document is written (available at PROCESSING).

**Format**: `job.format`, already present on `PrintJob`.

## Tier 1 wiring

`HttpRelay.forward()` streams the client's HTTP body straight to the USB channel via `BodyCopier.copy` without buffering it — this is intentional (multi-page jobs can be tens of MB) and must not change.

In `IppRelayServer.handleClient`, after parsing `HttpHead` and only when `Content-Type` is `application/ipp`:
1. Read exactly 4 bytes from `clientIn` (IPP packet: 1 byte version-major, 1 byte version-minor, 2 bytes operation-id — no attribute-group parsing, no document access).
2. Re-prepend those bytes with `SequenceInputStream(ByteArrayInputStream(peeked), clientIn)` so `HttpRelay.forward` sees an unmodified stream.
3. Map operation-id: `0x0002` (Print-Job), `0x0005` (Create-Job), `0x0006` (Send-Document) → produce an entry; anything else (Get-Printer-Attributes, etc.) → no entry, no peek overhead beyond the 4 bytes already read (which are always re-prepended regardless, so non-print IPP calls are unaffected functionally).
4. Size: `Content-Length` header from `HttpHead` (already parsed).
5. Client IP: `client.inetAddress` (`IppRelayServer.handleClient` already has the `Socket`).
6. Duration/status: wrap the `HttpRelay.forward()` call — record `ActivityLog.record(..., status = PRINTING)` before, `ActivityLog.update(id) { PRINTED }` after it returns, `FAILED` if it throws `IOException`.
7. Name: fixed string `"Print request"` — Tier 1 has no filename, by design (pure relay never parses the document or its metadata).

If `Content-Length` is absent (chunked transfer) size is left `null`; the UI shows no size for that entry rather than guessing.

## UI

New composable `ActivityCard` in `ui/PrintServerApp.kt`, placed in the "ACTIVE SHARING STATE VIEW" branch, immediately after the "Connection Specifications" card and before the "Stop Sharing" button. Same visual language as other cards: `PureWhite` background, `RoundedCornerShape(16.dp)`, `CardDefaults.cardElevation(2.dp)`.

- Header: "Recent Activity" label, same style as "Connection Specifications" header.
- Body: `LazyColumn` with `Modifier.heightIn(max = 320.dp)` — bounded viewport, session-unlimited backing list (scrolls internally; the card itself doesn't grow past 320dp regardless of entry count).
- Row (compact, collapsed):
  - 36dp icon box (`LightSlate` at 15% alpha background, `SlateBlue` print icon) — reuses the same icon-box pattern already used for the "Network" and "Printer" rows elsewhere in this file.
  - Name (bold, 14sp) + status row: colored 8dp dot (`#4CAF50` printed / `SlateBlue` printing / `#D32F2F` failed) + label ("Printed" / "Printing…" / "Failed" + `failureReason` if present).
  - Right-aligned relative time (`MediumGray`, 12sp) — "now" / "Xm ago" / "Xh ago", recomputed from `startedAt`/`completedAt`.
  - Row is clickable; tap toggles `expandedId` (local `remember { mutableStateOf<Int?>(null) }` in `ActivityCard`) — only one entry expanded at a time.
- Expanded detail (only when `expandedId == entry.id`): inline block below the row, `OffWhite` background, `RoundedCornerShape(8.dp)`, showing whichever of client address / size / duration / format / failure reason are non-null for that entry (Tier 1 entries lack `name` detail beyond the generic label, and have no duration breakdown beyond total wall time).
- Empty state (when `entries.isEmpty()`): centered print icon (reuse `Icons.Default.Print`, `MediumGray`, 50% alpha) + "No print jobs yet this session." in `MediumGray`.

`MainActivity` collects `ActivityLog.entries.collectAsStateWithLifecycle()` alongside the existing `ServerState.status` collection, passes the list into `PrintServerApp` as a new parameter, which forwards it to `ActivityCard`.

## Testing

- `ActivityLog`: JVM unit test — cap eviction at 200, `update` mutating the right entry, `clear()`.
- Tier 2: extend existing `JobQueueTest` (uses `FakePrinterTransport`/`FakeRenderingPipeline`) to assert `onJobStateChanged` fires with the expected `JobState` sequence per job outcome (completed / aborted / canceled / render-timeout).
- Tier 1: extend existing `IppRelayServer`/`HttpRelay` test harness with a synthetic Print-Job IPP request; assert (a) the byte stream `HttpRelay.forward` receives is byte-for-byte identical to what it would've received without the peek (relay correctness unaffected), and (b) exactly one `ActivityLog` entry results, with correct operation classification for a non-print op (e.g. Get-Printer-Attributes) producing none.
- UI: manual verification only (Compose on-device), per project convention — no JVM-testable UI layer here.

## Out of scope

- Persistence across app restarts.
- Filtering/searching the activity list.
- Any activity data in the mDNS TXT record or IPP `Get-Jobs` response (this feed is purely for the local UI).
