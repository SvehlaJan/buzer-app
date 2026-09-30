package app.buzer.android.time

import app.buzer.shared.time.parseSnapshotInstantOrNull
import java.time.Instant

/** java.time bridge for [parseSnapshotInstantOrNull], with the same strict contract. */
fun parseSnapshotJavaInstantOrNull(value: String): Instant? =
    parseSnapshotInstantOrNull(value)?.let { Instant.ofEpochSecond(it.epochSeconds, it.nanosecondsOfSecond.toLong()) }
