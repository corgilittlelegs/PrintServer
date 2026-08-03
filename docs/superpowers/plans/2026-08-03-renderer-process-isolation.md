# Renderer process isolation implementation plan

## 1. IPC boundary

- [x] Add a private AIDL renderer interface with PID and synchronous render operations.
- [x] Add an unexported `:renderer` Android service around `NativeRenderingPipeline`.
- [x] Validate IPC paths, strings, enum values, profile id, and single-call concurrency.
- [x] Add a debug-only forced-hang hook unreachable from advertised IPP formats.

## 2. Main-process pipeline

- [x] Add a lazy binding renderer-process client implementing `RenderingPipeline`.
- [x] Track Binder death and invalidate stale connections safely.
- [x] Verify renderer PID/name/UID before process termination.
- [x] Unbind/stop the renderer cleanly on queue shutdown.

## 3. Queue recovery

- [x] Add a recoverable-renderer contract.
- [x] On timeout, terminate the verified renderer and keep the queue usable.
- [x] Preserve queue poisoning and `onPipelineStuck` when recovery cannot be proven.
- [x] Keep timed-out jobs retryable and remove partial rendered output.

## 4. Verification

- [x] Add JVM tests for successful recovery, fallback poisoning, callback counts, and later jobs.
- [x] Add an Android test for remote native rendering after forced-hang process replacement.
- [x] Run the full JVM suite (311 tests), targeted native recovery fixture, lint, and debug APK build.
- [x] Install and verify the app process survives renderer death on the connected phone.
- [x] Confirm physical output from the post-recovery real-printer smoke job (page printed `Hello, Printer!`).
