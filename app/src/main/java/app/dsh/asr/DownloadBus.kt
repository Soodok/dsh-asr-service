package app.dsh.asr

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 应用级下载总线：模型下载跑在独立线程，跨页面持续进行；
 * 页面注册监听拿进度（主线程回调）。
 */
object DownloadBus {

    interface Listener {
        fun onProgress(modelId: String, done: Long, total: Long)
        fun onFinished(modelId: String, ok: Boolean, message: String?)
    }

    private const val TAG = "DownloadBus"
    private const val PROGRESS_MIN_INTERVAL_MS = 250L

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val flags = ConcurrentHashMap<String, AtomicBoolean>()
    private val main = Handler(Looper.getMainLooper())

    fun register(l: Listener) {
        listeners.addIfAbsent(l)
    }

    fun unregister(l: Listener) {
        listeners.remove(l)
    }

    fun isRunning(modelId: String): Boolean = flags.containsKey(modelId)

    fun cancel(modelId: String) {
        flags[modelId]?.set(true)
    }

    fun start(ctx: Context, model: AsrModel) {
        if (flags.containsKey(model.id)) return
        val appCtx = ctx.applicationContext
        val cancelFlag = AtomicBoolean(false)
        flags[model.id] = cancelFlag
        Thread({
            var ok = false
            var message: String? = null
            var lastPost = 0L
            try {
                val store = ModelStore(appCtx)
                ok = store.download(model, cancelFlag) { done, total, _ ->
                    val now = System.currentTimeMillis()
                    if (now - lastPost >= PROGRESS_MIN_INTERVAL_MS) {
                        lastPost = now
                        main.post { listeners.forEach { it.onProgress(model.id, done, total) } }
                    }
                }
                if (!ok) message = "网络或镜像异常（已重试 5 次）"
            } catch (e: DownloadCancelled) {
                message = "已取消"
            } catch (t: Throwable) {
                Log.w(TAG, "download failed: ${t.message}", t)
                message = t.message ?: t.javaClass.simpleName
            }
            flags.remove(model.id)
            val done = ok
            val msg = message
            main.post { listeners.forEach { it.onFinished(model.id, done, msg) } }
        }, "model-download").apply { isDaemon = true }.start()
    }
}
