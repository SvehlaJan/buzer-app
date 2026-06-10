package app.buzer.android.enforcement

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BootReconcileRunnerTest {
    @Test
    fun runWorkReconcilesCacheBeforeRefreshingLiveSnapshot() = runBlocking {
        val events = mutableListOf<String>()
        val logs = mutableListOf<String>()

        BootReconcileRunner.runWork(
            reconcileLatestSnapshot = {
                events += "cache"
                true
            },
            refreshAndReconcile = {
                events += "refresh"
            },
            refreshTimeoutMillis = 1_000,
            logger = logs::add
        )

        assertEquals(listOf("cache", "refresh"), events)
        assertTrue(logs.any { it == "boot_reconcile cached=true" })
        assertTrue(logs.any { it == "boot_reconcile refresh=success" })
        assertTrue(logs.any { it == "boot_reconcile complete" })
    }

    @Test
    fun runWorkLogsRefreshTimeoutAndCompletes() = runBlocking {
        val logs = mutableListOf<String>()

        BootReconcileRunner.runWork(
            reconcileLatestSnapshot = { false },
            refreshAndReconcile = {
                delay(50)
            },
            refreshTimeoutMillis = 1,
            logger = logs::add
        )

        assertTrue(logs.any { it == "boot_reconcile cached=false" })
        assertTrue(logs.any { it == "boot_reconcile refresh=timeout" })
        assertTrue(logs.any { it == "boot_reconcile complete" })
    }
}
