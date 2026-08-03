package dev.jaspreet.printserver.render

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import dev.jaspreet.printserver.jobs.ColorMode
import dev.jaspreet.printserver.jobs.PrintQuality
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Main-process proxy for the disposable native `:renderer` service. */
class RendererProcessPipeline(
    context: Context,
    private val profileId: String,
    private val bindTimeoutMs: Long = 10_000L,
    private val terminationTimeoutMs: Long = 5_000L,
) : RecoverableRenderingPipeline {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val activeRendererPid = AtomicInteger(0)
    private val lastRendererPid = AtomicInteger(0)
    @Volatile private var closed = false
    private var binding: Binding? = null

    override fun render(
        document: File,
        output: File,
        format: String,
        quality: PrintQuality,
        colorMode: ColorMode,
    ) {
        val remote = renderer()
        val pid = try {
            remote.rendererPid
        } catch (e: RemoteException) {
            invalidateBinding()
            throw IOException("Renderer process is unavailable", e)
        }
        if (!isExpectedRenderer(pid)) {
            invalidateBinding()
            throw IOException("Renderer process identity could not be verified")
        }
        activeRendererPid.set(pid)
        lastRendererPid.set(pid)
        try {
            val error = remote.render(
                document.absolutePath,
                output.absolutePath,
                format,
                quality.name,
                colorMode.name,
                profileId,
            )
            if (error.isNotEmpty()) throw IOException(error)
        } catch (e: RemoteException) {
            invalidateBinding()
            throw IOException("Renderer process died", e)
        } finally {
            activeRendererPid.compareAndSet(pid, 0)
        }
    }

    override fun recoverFromTimeout(): Boolean {
        val pid = activeRendererPid.get()
        if (!isExpectedRenderer(pid)) return false
        val oldBinding = detachBinding()
        return try {
            Process.killProcess(pid)
            oldBinding?.unbind()
            val deadline = System.currentTimeMillis() + terminationTimeoutMs
            while (isExpectedRenderer(pid) && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            activeRendererPid.compareAndSet(pid, 0)
            !isExpectedRenderer(pid)
        } catch (_: Exception) {
            false
        }
    }

    override fun close() {
        closed = true
        val pid = activeRendererPid.get()
        val oldBinding = detachBinding()
        if (isExpectedRenderer(pid)) {
            try { Process.killProcess(pid) } catch (_: Exception) {}
        }
        oldBinding?.unbind()
        appContext.stopService(Intent(appContext, RendererProcessService::class.java))
        activeRendererPid.set(0)
    }

    internal fun activeRendererPidForTest(): Int = activeRendererPid.get()
    internal fun lastRendererPidForTest(): Int = lastRendererPid.get()

    private fun renderer(): IRendererService {
        val current = synchronized(lock) {
            if (closed) throw IOException("Renderer pipeline is closed")
            val existing = binding
            if (existing != null && !existing.dead) {
                existing
            } else {
                Binding().also { created ->
                    binding = created
                    created.bind()
                }
            }
        }
        if (!current.connected.await(bindTimeoutMs, TimeUnit.MILLISECONDS)) {
            synchronized(lock) { if (binding === current) binding = null }
            current.unbind()
            throw IOException("Timed out binding renderer process")
        }
        return current.service?.takeIf { it.asBinder().isBinderAlive }
            ?: throw IOException("Renderer process failed to bind")
    }

    private fun invalidateBinding() {
        detachBinding()?.unbind()
    }

    private fun detachBinding(): Binding? = synchronized(lock) {
        binding.also { binding = null }
    }

    private fun isExpectedRenderer(pid: Int): Boolean {
        if (pid <= 0 || pid == Process.myPid()) return false
        val expectedName = "${appContext.packageName}:renderer"
        val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return activityManager.runningAppProcesses.orEmpty().any {
            it.pid == pid && it.uid == Process.myUid() && it.processName == expectedName
        }
    }

    private inner class Binding : ServiceConnection {
        val connected = CountDownLatch(1)
        @Volatile var service: IRendererService? = null
        @Volatile var dead = false
        @Volatile private var bound = false

        fun bind() {
            bound = appContext.bindService(
                Intent(appContext, RendererProcessService::class.java),
                this,
                Context.BIND_AUTO_CREATE,
            )
            if (!bound) {
                dead = true
                connected.countDown()
            }
        }

        fun unbind() {
            if (!bound) return
            bound = false
            try { appContext.unbindService(this) } catch (_: IllegalArgumentException) {}
        }

        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null) {
                dead = true
            } else {
                service = IRendererService.Stub.asInterface(binder)
            }
            connected.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) = markDead()
        override fun onBindingDied(name: ComponentName?) = markDead()
        override fun onNullBinding(name: ComponentName?) = markDead()

        private fun markDead() {
            dead = true
            service = null
            connected.countDown()
            val wasCurrent = synchronized(lock) {
                if (binding === this) {
                    binding = null
                    true
                } else {
                    false
                }
            }
            if (wasCurrent) unbind()
        }
    }
}
