package co.featbit.android.internal

import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

internal interface SyncHandle { fun cancel(); fun send(text: String): Boolean }
internal interface SyncListener {
    fun opened()
    fun message(text: String)
    fun response(status: Int, body: String, retryAfter: String?)
    fun failed(status: Int?, retryAfter: String? = null)
    fun ended(code: Int)
}
internal interface SyncTransport {
    fun start(request: Request, streaming: Boolean, listener: SyncListener): SyncHandle
    fun close()
}

/** Owned by one client. No redirects, implicit HTTP retries, or shared application resources. */
internal class OkHttpSyncTransport : SyncTransport {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS).writeTimeout(0, TimeUnit.MILLISECONDS).build()
    private val slots = Semaphore(4)
    private val closed = AtomicBoolean()
    override fun start(request: Request, streaming: Boolean, listener: SyncListener): SyncHandle {
        if (closed.get() || !slots.tryAcquire()) { listener.failed(null); return EmptyHandle }
        val ended = AtomicBoolean()
        fun release() { if (ended.compareAndSet(false, true)) slots.release() }
        try {
            if (streaming) {
                val socket = client.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) { listener.opened() }
                    override fun onMessage(webSocket: WebSocket, text: String) { listener.message(text) }
                    override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) { listener.message("") }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        // Cancellation bounds cleanup even if the peer never finishes its close handshake.
                        listener.ended(code); webSocket.cancel()
                    }
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { release(); listener.ended(code) }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        release(); listener.failed(response?.code, response?.header("Retry-After")); response?.close()
                    }
                })
                return object : SyncHandle {
                    override fun cancel() { socket.cancel() }
                    override fun send(text: String) = socket.send(text)
                }
            }
            val call = client.newCall(request)
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { release(); listener.failed(null) }
                override fun onResponse(call: Call, response: Response) {
                    try { response.use { listener.response(it.code, if (it.code == 200) it.body?.string() ?: "" else "", it.header("Retry-After")) } }
                    catch (_: IOException) { listener.failed(null) }
                    finally { release() }
                }
            })
            return object : SyncHandle {
                override fun cancel() { call.cancel() }
                override fun send(text: String) = false
            }
        } catch (_: Exception) { release(); listener.failed(null); return EmptyHandle }
    }
    override fun close() {
        closed.set(true); client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
    }
    private object EmptyHandle : SyncHandle { override fun cancel() = Unit; override fun send(text: String) = false }
}
