package app.buzer.android.morningreview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MorningReviewReconcileGuardTest {
    @Test
    fun deniedPermissionSkipsReconcileAndReportsDegradedState() {
        var calls = 0
        val reports = mutableListOf<String>()

        MorningReviewReconcileGuard.reconcile(
            permitted = false,
            action = { calls++ },
            report = reports::add
        )

        assertEquals(0, calls)
        assertEquals(listOf("exact_alarm_permission_denied"), reports)
    }

    @Test
    fun permissionRaceDoesNotEscapeAndReportsDegradedState() {
        val reports = mutableListOf<String>()

        MorningReviewReconcileGuard.reconcile(
            permitted = true,
            action = { throw SecurityException("permission revoked") },
            report = reports::add
        )

        assertEquals(listOf("exact_alarm_permission_race"), reports)
    }

    @Test
    fun permittedReconcileRunsWithoutReport() {
        var calls = 0
        val reports = mutableListOf<String>()

        MorningReviewReconcileGuard.reconcile(
            permitted = true,
            action = { calls++ },
            report = reports::add
        )

        assertEquals(1, calls)
        assertEquals(emptyList<String>(), reports)
    }

    @Test
    fun nonPermissionFailureStillEscapes() {
        assertThrows(IllegalStateException::class.java) {
            MorningReviewReconcileGuard.reconcile(
                permitted = true,
                action = { error("storage failed") },
                report = {}
            )
        }
    }
}
