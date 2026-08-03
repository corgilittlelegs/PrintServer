# Renderer process isolation design

## Problem

`JobQueue` currently detects a render that exceeds 120 seconds, but Ghostscript and hpcups run as
native calls inside the app process. Interrupting the Kotlin future cannot stop native code safely.
The queue therefore poisons itself and stops the foreground service to avoid a second call racing
the still-running native singleton. This detects a hang but does not enforce a real execution cap.

## Process boundary

Native rendering moves into a private Android service running as `:renderer`. The foreground
`ServerService`, IPP/eSCL/raw servers, job queue, discovery, USB transport, and UI remain in the main
process. The renderer service is not exported and runs under the same application UID so it can
read and write the app-private spool files by canonical path.

The main process binds through a small synchronous AIDL interface. A request contains input/output
paths, document format, quality, color mode, and verified profile id. The renderer constructs the
existing `NativeRenderingPipeline` and returns an empty error string on success or a bounded error
message on a caught rendering failure. A native crash appears as Binder death. The service refuses
concurrent render calls, preserving the existing non-reentrant hpcups/Ghostscript assumption.

This is process isolation, not Android's `isolatedProcess=true`: the latter uses a different UID and
cannot safely access the existing private spool/PPD paths without redesigning the native APIs around
file descriptors.

## Hard timeout recovery

`JobQueue` retains the wall-clock deadline around `RenderingPipeline.render`. On timeout it first
asks a `RecoverableRenderingPipeline` to terminate its active renderer, then cancels the waiting
future. This ordering preserves the verified active PID until termination has been attempted.
Termination is considered successful only when the recorded PID is not the main PID and Android's
running-process table confirms the PID, UID, and exact `package:renderer` process name. The main
process then sends `killProcess` to that verified PID, invalidates/unbinds the dead Binder, marks the
job `ABORTED` with `render-timeout`, deletes partial output, and continues accepting later jobs.

If no verified renderer PID can be identified or termination fails, the old fail-safe remains:
poison the queue and invoke `onPipelineStuck`. A safety failure therefore never permits concurrent
native renderers.

The next job lazily binds a fresh renderer process. Killing the renderer cannot close the main
process's USB transport because the remote process performs rendering only; printer bytes are still
written by `JobQueue` after a successful render returns.

## Lifecycle and failure behavior

- Ordinary render exceptions abort only that job; the renderer process stays reusable.
- Native renderer crashes abort that job through Binder failure; the next job rebinds.
- Queue shutdown unbinds and stops the private renderer service, killing it if a verified active
  native call remains.
- Stale partial output is removed by the existing `finally` cleanup.
- ABORTED input spools remain retryable under the existing retry policy.
- A debug-only, non-advertised test format can deliberately block the renderer for instrumentation;
  production IPP capability validation can never submit that format.

## Security boundaries

The renderer service is `exported=false`; only this application can bind it. It verifies that input,
output, and PPD files resolve inside the app's private data directory before native code runs. IPC
strings are length-bounded, enum names are parsed strictly, and error messages returned across Binder
are truncated. The service does not own USB, network sockets, discovery, or persistent settings.

## Verification

Plain JVM tests prove that a recoverable timeout does not poison the queue, the timed-out job is
reported exactly once, and a later job renders/writes normally; a failed recovery retains poisoning.
Android instrumentation proves a forced native-thread hang causes only `:renderer` to die and a
subsequent PDF render succeeds in a new PID while the target app process remains alive. Existing
native PDF/PWG fixtures and the complete JVM suite remain green.
