package app.buzer.android.sync

import app.buzer.shared.model.HealthStatus
import app.buzer.shared.model.TasksSnapshotResponse
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SnapshotStoreTest {
    @Test
    fun returnsNullWhenSnapshotFileIsMissing() {
        val store = SnapshotStore(Files.createTempDirectory("missing-snapshot").toFile())

        assertNull(store.load())
    }

    @Test
    fun roundTripsSnapshotJson() {
        val store = SnapshotStore(Files.createTempDirectory("round-trip-snapshot").toFile())
        val snapshot = TasksSnapshotResponse(
            scheduleVersion = 7,
            serverTime = "2026-05-28T09:00:00Z",
            tasks = emptyList(),
            health = emptyList<HealthStatus>()
        )

        store.save(snapshot)

        assertEquals(snapshot, store.load())
    }

    @Test
    fun corruptedSnapshotIsRecoverable() {
        val directory = Files.createTempDirectory("corrupted-snapshot").toFile()
        val fileName = "corrupted_snapshot.json"
        directory.resolve(fileName).writeText("{not-json")
        val store = SnapshotStore(directory, fileName = fileName)

        val result = runCatching { store.load() }

        assertTrue(result.isFailure)
        store.save(TasksSnapshotResponse(1, "2026-05-28T09:00:00Z", emptyList(), emptyList()))
        assertEquals(1, store.load()?.scheduleVersion)
    }
}
