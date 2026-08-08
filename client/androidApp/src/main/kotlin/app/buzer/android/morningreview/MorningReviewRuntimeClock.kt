package app.buzer.android.morningreview

import java.time.Instant
import java.time.ZoneId

/** Runtime clock with an internal instrumentation override; production always uses system time. */
internal object MorningReviewRuntimeClock {
    @Volatile private var override: (() -> Pair<Instant, ZoneId>)? = null

    data class Snapshot(val instant: Instant, val zoneId: ZoneId)

    fun snapshot(): Snapshot = (override?.invoke() ?: (Instant.now() to ZoneId.systemDefault()))
        .let { Snapshot(it.first, it.second) }
    fun now(): Instant = snapshot().instant
    fun zoneId(): ZoneId = snapshot().zoneId

    fun setForTests(clock: (() -> Pair<Instant, ZoneId>)?) {
        override = clock
    }
}
