package app.buzer.shared.time

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class SnapshotInstantTest {
    private val expected = Instant.parse("2026-10-11T07:00:00Z")

    @Test
    fun parsesUtcInstants() = assertEquals(expected, parseSnapshotInstantOrNull("2026-10-11T07:00:00Z"))

    @Test
    fun parsesExplicitOffsets() = assertEquals(expected, parseSnapshotInstantOrNull("2026-10-11T09:00:00+02:00"))

    @Test
    fun parsesTodoistMicroseconds() = assertEquals(expected, parseSnapshotInstantOrNull("2026-10-11T07:00:00.000000Z"))

    @Test
    fun ignoresBracketedZoneSuffix() = assertEquals(expected, parseSnapshotInstantOrNull("2026-10-11T07:00:00Z[UTC]"))

    @Test
    fun rejectsFloatingValuesInsteadOfGuessingATimezone() = assertNull(parseSnapshotInstantOrNull("2026-10-11T09:00:00"))

    @Test
    fun rejectsGarbage() = assertNull(parseSnapshotInstantOrNull("not-a-date"))
}
