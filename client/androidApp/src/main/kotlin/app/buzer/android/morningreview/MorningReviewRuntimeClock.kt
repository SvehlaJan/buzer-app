package app.buzer.android.morningreview

import java.time.Instant
import java.time.ZoneId

/** Runtime clock with an internal instrumentation override; production always uses system time. */
internal object MorningReviewRuntimeClock {
    @Volatile private var override: (() -> Pair<Instant, ZoneId>)? = null

    fun now(): Instant = (override?.invoke() ?: (Instant.now() to ZoneId.systemDefault())).first
    fun zoneId(): ZoneId = (override?.invoke() ?: (Instant.now() to ZoneId.systemDefault())).second

    fun setForTests(clock: (() -> Pair<Instant, ZoneId>)?) {
        override = clock
    }
}
