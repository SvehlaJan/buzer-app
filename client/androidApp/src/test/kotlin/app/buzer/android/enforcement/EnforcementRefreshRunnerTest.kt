package app.buzer.android.enforcement

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnforcementRefreshRunnerTest {
    @Test
    fun skipsRefreshWhilePreviousRefreshIsStillRunning() = runBlocking {
        val firstRefreshCanFinish = CompletableDeferred<Unit>()
        var refreshCount = 0
        val runner = EnforcementRefreshRunner(scope = this, reportFailure = {})

        val firstJob = runner.refresh {
            refreshCount += 1
            firstRefreshCanFinish.await()
        }
        yield()

        val secondJob = runner.refresh {
            refreshCount += 1
        }

        assertNotNull(firstJob)
        assertNull(secondJob)
        assertEquals(1, refreshCount)

        firstRefreshCanFinish.complete(Unit)
        firstJob.join()
    }

    @Test
    fun cancellationDoesNotReportRefreshFailure() = runBlocking {
        val reportedFailures = mutableListOf<Throwable>()
        val runner = EnforcementRefreshRunner(scope = this, reportFailure = reportedFailures::add)

        val job = runner.refresh {
            throw CancellationException("destroyed")
        }

        assertNotNull(job)
        job.join()
        assertTrue(reportedFailures.isEmpty())
    }
}
