package app.buzer.android.sync

import app.buzer.shared.model.HealthStatus
import app.buzer.shared.model.TasksSnapshotResponse
import java.io.IOException
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
            health = emptyList<HealthStatus>(),
            snapshotGeneration = 7
        )

        store.save(snapshot)

        assertEquals(snapshot, store.load())
    }

    @Test
    fun migratesLegacyPersistedSnapshotWithoutGenerationToZero() {
        val directory = Files.createTempDirectory("legacy-snapshot").toFile()
        val messages = mutableListOf<String>()
        directory.resolve("latest_snapshot.json").writeText(
            """{"scheduleVersion":7,"serverTime":"2026-05-28T09:00:00Z","health":[],"tasks":[]}"""
        )
        val store = SnapshotStore(directory, logger = messages::add)

        val snapshot = store.load()

        assertEquals(0, snapshot?.snapshotGeneration)
        assertTrue(directory.resolve("latest_snapshot.json").readText().contains("\"snapshotGeneration\":0"))
        assertTrue(messages.any { it.contains("legacy generation migration") })
    }

    @Test
    fun returnsLegacySnapshotWhenGenerationMigrationRewriteFails() {
        val directory = Files.createTempDirectory("legacy-snapshot-rewrite-failure").toFile()
        val messages = mutableListOf<String>()
        val legacyJson =
            """{"scheduleVersion":7,"serverTime":"2026-05-28T09:00:00Z","health":[],"tasks":[]}"""
        directory.resolve("latest_snapshot.json").writeText(legacyJson)
        val store = SnapshotStore(
            directory = directory,
            logger = messages::add,
            atomicWriter = { _, _ -> throw IOException("disk full") }
        )

        val snapshot = store.load()

        assertEquals(0, snapshot?.snapshotGeneration)
        assertEquals(legacyJson, directory.resolve("latest_snapshot.json").readText())
        assertTrue(messages.any { it.contains("legacy generation migration failed") })
    }

    @Test
    fun corruptedSnapshotIsRecoverable() {
        val directory = Files.createTempDirectory("corrupted-snapshot").toFile()
        val fileName = "corrupted_snapshot.json"
        directory.resolve(fileName).writeText("{not-json")
        val store = SnapshotStore(directory, fileName = fileName)

        val result = runCatching { store.load() }

        assertTrue(result.isFailure)
        store.save(
            TasksSnapshotResponse(
                scheduleVersion = 1,
                serverTime = "2026-05-28T09:00:00Z",
                tasks = emptyList(),
                health = emptyList(),
                snapshotGeneration = 1
            )
        )
        assertEquals(1, store.load()?.scheduleVersion)
    }
}
