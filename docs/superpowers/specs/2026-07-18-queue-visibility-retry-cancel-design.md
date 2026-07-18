# Queue visibility + retry/cancel — design

Date: 2026-07-18

## Context

Tier 2 (`JobQueue`) already has `cancel(id)` and `listActive()` server-side, but no UI
wires to them, and there's no way to retry a failed job — `JobQueue.process()` deletes
`job.spoolFile` unconditionally in its `finally` block on every terminal state.

Tier 1 (IPP-USB relay) is out of scope: it's a synchronous byte relay per HTTP request
with no queue concept, so cancel/retry/queue-position don't apply there.

This is the first of several print-management features being added on top of the
existing relay/render architecture (see `CLAUDE.md`); print options and ink-level
reporting are separate, later sub-projects.

## Scope

- Retry a job that failed (`JobState.ABORTED`) by resubmitting its original spool bytes.
- Cancel a job still waiting (`JobState.PENDING`) — already possible server-side, just
  needs a UI affordance.
- Show the live queue (PENDING + PROCESSING jobs) as its own UI section, separate from
  the historical `ActivityCard` feed.

Explicitly not in scope: canceling/interrupting a job that's already `PROCESSING`
(native Ghostscript/hpcups pipeline isn't safely interruptible — matches existing
`JobQueue.cancel()` behavior, which already no-ops on non-PENDING jobs). Retry for
user-`CANCELED` jobs is also not in scope — cancel remains an immediate, final action
with immediate spool deletion, same as today.

## Design

### `PrintJob` (`app/src/main/java/dev/jaspreet/printserver/jobs/PrintJob.kt`)

Add two fields:
- `submittedAtMs: Long` — set at construction (`System.currentTimeMillis()`), used for
  elapsed-time display in the queue UI.
- `retryOf: Int? = null` — set when a job was created via `retry()`, points at the
  original job id. Tracking/debugging only, not used for any control-flow decision.

### `JobQueue` (`app/src/main/java/dev/jaspreet/printserver/jobs/JobQueue.kt`)

- `process()`'s `finally` block stops deleting `job.spoolFile` when the terminal state
  is `ABORTED`. (`COMPLETED` and `CANCELED` keep deleting immediately as today.) The
  spool file for an aborted job now lives until either:
  - it's evicted by `evictOldTerminalJobs()` (still the 200-cap, oldest-terminal-first),
    which must now also `spoolFile.delete()` for whatever it drops — previously
    unnecessary, since every terminal job's file was already gone by the time eviction
    ran.
  - the app/service restarts and `cleanStaleSpool()` wipes the whole spool dir on next
    `startLegacyPipeline()` — already existing behavior, no change needed.
- New method: `retry(id: Int): Int?`
  - Looks up the job; returns `null` unless `state == ABORTED` and `spoolFile.exists()`.
  - Otherwise calls `submit(spoolFile, name, format, clientAddress)` to create a new job
    reusing the same on-disk bytes, sets `retryOf = id` on the new `PrintJob`, and
    returns its new id.
  - If the queue is currently `poisoned`, the resubmitted job still goes through
    `submit()` and the existing poisoned-check in the worker loop fails it immediately
    via `failWithoutRendering` with `stateReason = "queue-unavailable"` — no special
    handling needed, this is the same path any other job takes once poisoned.
- Queue position for a PENDING job = its rank by job id among all currently-PENDING
  jobs (ascending). No new bookkeeping needed — `nextId` is already a strictly
  increasing `AtomicInteger`, so id order == submission order == FIFO order.

### `ActivityEntry` (`app/src/main/java/dev/jaspreet/printserver/activity/ActivityLog.kt`)

Add `jobId: Int? = null` — the underlying Tier-2 `PrintJob.id`, so the UI can call
`queue.retry(entry.jobId)` / `queue.cancel(entry.jobId)` directly from an activity row.
Populated in `ServerService`'s `onJobStateChanged` callback alongside the existing
`jobActivityIds` map (which goes the other direction: job id → activity id).

### `ServerService` (`app/src/main/java/dev/jaspreet/printserver/service/ServerService.kt`)

Exposes the live queue as a `StateFlow<List<PrintJob>>` (or an equivalent snapshot list
updated on the same `onJobStateChanged` firing already used for `ActivityLog`), scoped
to the same session as `jobQueue` itself — cleared when the service stops, same
lifecycle as `ActivityLog.clear()` today.

### UI (`app/src/main/java/dev/jaspreet/printserver/ui/PrintServerApp.kt`)

- New `QueueCard` composable, rendered above the existing `ActivityCard`. One row per
  PENDING/PROCESSING job: name, status ("Queued" / "Printing…"), queue position (PENDING
  only — "2nd in queue"), elapsed time since `submittedAtMs`, file size. Cancel button on
  PENDING rows only, calling `jobQueue.cancel(job.id)` and letting the existing
  `onJobStateChanged` → `StateFlow` update drive the row's removal.
- `ActivityRow` gets a Retry button on `ActivityStatus.FAILED` rows where
  `entry.jobId != null`, calling `jobQueue.retry(entry.jobId)`. If it returns `null`
  (spool already evicted), show a transient message — "job no longer available to
  retry" — rather than silently doing nothing.

## Error handling

- `retry()` returns `null` for anything but an eligible `ABORTED` job with an existing
  spool file — UI surfaces this as a message, not a crash or silent no-op.
- Cancel race (PENDING → PROCESSING between render and tap): already handled atomically
  by `JobQueue.cancel()`'s `synchronized(job)` state check; a false "couldn't cancel" is
  acceptable, no special UI handling needed.
- Disk usage from retained failed-job spool files is bounded by the existing
  `MAX_RETAINED_JOBS` (200) cap and the existing `checkFreeSpace` 200MB floor check on
  the next render — no new safeguard required.

## Testing

JVM unit tests only (`app/src/test/`), extending existing `JobQueue`-style coverage with
`FakePrinterTransport`/`FakeRenderingPipeline`:

- `retry()` on an `ABORTED` job resubmits and returns a new id; the new job's spool file
  is the same bytes as the original.
- `retry()` on `COMPLETED` / `CANCELED` / `PENDING` / `PROCESSING` jobs returns `null`.
- `retry()` after the original job has been evicted returns `null`.
- `evictOldTerminalJobs()` deletes the spool file of any `ABORTED` job it evicts.
- Queue position is computed correctly for a batch of several PENDING jobs.

No `androidTest` needed — this touches `JobQueue`/`PrintJob` logic and Compose UI only,
no native/USB surface.

Manual verification (see `docs/superpowers/testing/hardware-smoke-checklist.md` for the
general checklist this augments): submit a job that fails (malformed PDF) and confirm
Retry appears and resubmits successfully; queue 2+ jobs and confirm position/elapsed
time are correct; cancel a PENDING job mid-queue and confirm it disappears from
`QueueCard` and shows as failed/canceled in the activity feed.
