package dev.kolektiv.kalendee.auth

import dev.kolektiv.kalendee.calendar.CalendarException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

internal class LoginThrottle(
    private val clock: Clock,
    private val maxAttempts: Int = 5,
    private val window: Duration = 60.seconds,
) {
    private data class Bucket(val count: Int, val resetAt: Instant)

    private val buckets = ConcurrentHashMap<String, Bucket>()

    fun check(key: String) {
        val now = clock.now()
        val bucket = buckets[key] ?: return
        if (now >= bucket.resetAt) {
            buckets.remove(key, bucket)
            return
        }
        if (bucket.count >= maxAttempts) {
            throw CalendarException.TooManyRequests("too many login attempts")
        }
    }

    fun recordFailure(key: String) {
        val now = clock.now()
        buckets.compute(key) { _, existing ->
            if (existing == null || now >= existing.resetAt) {
                Bucket(1, now + window)
            } else {
                existing.copy(count = existing.count + 1)
            }
        }
    }

    fun recordSuccess(key: String) {
        buckets.remove(key)
    }
}
