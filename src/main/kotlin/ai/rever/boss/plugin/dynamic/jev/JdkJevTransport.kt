package ai.rever.boss.plugin.dynamic.jev

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.nio.ByteBuffer
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

class JdkJevTransport internal constructor(
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(10))
        .build(),
    private val endpoint: URI = ENDPOINT,
) : JevTransport {
    private val lifecycleLock = Any()
    private val active = mutableSetOf<CompletableFuture<*>>()
    private var closed = false

    override suspend fun post(
        body: ByteArray,
        bearerToken: String,
        timeoutMs: Long,
        maxResponseBytes: Int,
    ): ByteArray {
        val request = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofMillis(timeoutMs))
            .header("Authorization", "Bearer $bearerToken")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
        val future = synchronized(lifecycleLock) {
            if (closed) throw JevFailure("SERVICE_UNAVAILABLE", "Jev is unloading")
            client.sendAsync(request, limitingBodyHandler(maxResponseBytes)).also(active::add)
        }
        val response = try {
            future.awaitCancellable()
        } finally {
            synchronized(lifecycleLock) { active -= future }
        }
        return when (response.statusCode()) {
            in 200..299 -> response.body()
            401, 403 -> throw JevFailure("AUTH_ERROR", "OpenRouter rejected the configured key")
            422 -> throw JevFailure("UPSTREAM_INVALID_INPUT", "OpenRouter rejected the Jev request")
            429 -> throw JevFailure("RATE_LIMITED", "OpenRouter rate limit reached; try again later")
            in 500..599 -> throw JevFailure("UPSTREAM_ERROR", "OpenRouter is temporarily unavailable")
            else -> throw JevFailure("UPSTREAM_ERROR", "OpenRouter returned HTTP ${response.statusCode()}")
        }
    }

    override fun cancelAll() {
        val pending = synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            active.toList().also { active.clear() }
        }
        pending.forEach { it.cancel(true) }
    }

    private fun limitingBodyHandler(limit: Int): HttpResponse.BodyHandler<ByteArray> =
        HttpResponse.BodyHandler { info ->
            val advertised = info.headers().firstValueAsLong("Content-Length").orElse(-1)
            LimitedBodySubscriber(limit, advertised)
        }

    private class LimitedBodySubscriber(
        private val limit: Int,
        private val advertisedLength: Long,
    ) : HttpResponse.BodySubscriber<ByteArray> {
        private val result = CompletableFuture<ByteArray>()
        private val output = ByteArrayOutputStream(minOf(limit, 8192))
        private var subscription: Flow.Subscription? = null

        override fun getBody(): CompletionStage<ByteArray> = result

        override fun onSubscribe(subscription: Flow.Subscription) {
            this.subscription = subscription
            if (advertisedLength > limit) {
                subscription.cancel()
                tooLarge()
            } else {
                subscription.request(1)
            }
        }

        override fun onNext(item: List<ByteBuffer>) {
            try {
                item.forEach { buffer ->
                    val count = buffer.remaining()
                    if (output.size() + count > limit) {
                        subscription?.cancel()
                        tooLarge()
                        return
                    }
                    val bytes = ByteArray(count)
                    buffer.get(bytes)
                    output.write(bytes)
                }
                subscription?.request(1)
            } catch (error: Throwable) {
                subscription?.cancel()
                result.completeExceptionally(error)
            }
        }

        override fun onError(throwable: Throwable) { result.completeExceptionally(throwable) }
        override fun onComplete() { result.complete(output.toByteArray()) }

        private fun tooLarge() {
            result.completeExceptionally(
                JevFailure("RESPONSE_TOO_LARGE", "Response exceeds plugin limit $limit bytes"),
            )
        }
    }

    private suspend fun <T> CompletableFuture<T>.awaitCancellable(): T =
        suspendCancellableCoroutine { continuation ->
            whenComplete { value, error ->
                if (error == null) continuation.resume(value)
                else continuation.resumeWithException(error.cause ?: error)
            }
            continuation.invokeOnCancellation { cancel(true) }
        }

    companion object {
        val ENDPOINT: URI = URI.create("https://openrouter.ai/api/v1/systemone")
    }
}
