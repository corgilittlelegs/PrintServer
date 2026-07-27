package dev.jaspreet.printserver.render

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runtime tripwire against overlapping invocations of a non-reentrant native resource.
 *
 * hpcups's JNI entry points (see `hpcupsjni.cpp`) mutate process-global C statics and are
 * not reentrant — two calls in flight at once means silent corruption of that shared state,
 * not a clean crash. [JobQueue][dev.jaspreet.printserver.jobs.JobQueue]'s single-threaded
 * `renderExecutor` is what structurally guarantees only one call happens at a time today;
 * this guard is an independent, always-on check on top of that guarantee, so a future
 * refactor that accidentally introduces a second caller (or a bug that lets a retry race a
 * still-running call) fails loudly and immediately instead of corrupting state or deadlocking.
 *
 * Deliberately not a lock/mutex: a genuine overlap here is always a bug, so the right
 * response is to throw immediately, not to queue the second caller behind the first (which
 * would hide the bug and, worse, could deadlock if the "second" call is actually a reentrant
 * call on the same thread).
 *
 * Kept as a plain, dependency-free class (not nested inside `HpcupsNative`) so it can be
 * exercised directly by JVM unit tests without tripping `HpcupsNative`'s `init { System.loadLibrary(...) }`.
 */
class NativeCallGuard(private val label: String) {
    private val busy = AtomicBoolean(false)

    /**
     * Runs [block], throwing [IllegalStateException] instead of running it if another
     * call is already in progress. Clears the busy flag on any exit path (success,
     * exception from [block]) so the guard is reusable for the next call.
     */
    fun <T> guarded(block: () -> T): T {
        check(busy.compareAndSet(false, true)) {
            "$label: overlapping native call detected — this native resource is not " +
                "reentrant and all calls must be serialized (see NativeCallGuard's kdoc)."
        }
        try {
            return block()
        } finally {
            busy.set(false)
        }
    }
}
