package app.buzer.shared.time

import kotlin.time.Instant

/**
 * Parses a timestamp from a backend snapshot. The backend always sends an offset
 * (decision 2026-09-29-reminder-reliability); an offset-less value is a contract violation
 * and yields null instead of a guessed timezone.
 */
fun parseSnapshotInstantOrNull(value: String): Instant? =
    runCatching { Instant.parse(value.substringBefore('[')) }.getOrNull()
