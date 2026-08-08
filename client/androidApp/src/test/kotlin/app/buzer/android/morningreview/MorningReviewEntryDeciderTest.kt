package app.buzer.android.morningreview

import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MorningReviewEntryDeciderTest {
    @Test
    fun `unacknowledged midnight shows banner without automatic launch`() {
        val decision = MorningReviewEntryDecider.decide(availability(banner = true, catchUp = false), launchInFlight = false)

        assertTrue(decision.showHomeBanner)
        assertFalse(decision.launchAutomated)
    }

    @Test
    fun `catch up launches once unless a review is in flight`() {
        val availability = availability(banner = true, catchUp = true)

        assertTrue(MorningReviewEntryDecider.decide(availability, launchInFlight = false).launchAutomated)
        assertFalse(MorningReviewEntryDecider.decide(availability, launchInFlight = true).launchAutomated)
    }

    @Test
    fun `active snooze hides banner and automatic launch`() {
        val decision = MorningReviewEntryDecider.decide(availability(banner = false, catchUp = false, snoozed = true), launchInFlight = false)

        assertFalse(decision.showHomeBanner)
        assertFalse(decision.launchAutomated)
    }

    @Test
    fun `same day repair reopening shows banner without automatic launch`() {
        val decision = MorningReviewEntryDecider.decide(availability(banner = true, catchUp = false), launchInFlight = false)

        assertTrue(decision.showHomeBanner)
        assertFalse(decision.launchAutomated)
    }

    @Test
    fun `default on create does not launch until resume`() {
        val controller = MorningReviewEntryController()

        assertFalse(controller.evaluate(availability(banner = true, catchUp = true)).launchAutomated)
    }

    @Test
    fun `resume catch up launches once and second evaluation is guarded`() {
        val controller = MorningReviewEntryController()
        controller.onResume()

        assertTrue(controller.evaluate(availability(banner = true, catchUp = true)).launchAutomated)
        assertFalse(controller.evaluate(availability(banner = true, catchUp = true)).launchAutomated)
    }

    @Test
    fun `pause prevents async evaluation and home launch`() {
        val controller = MorningReviewEntryController()
        controller.onResume()
        controller.onPause()

        assertFalse(controller.evaluate(availability(banner = true, catchUp = true)).launchAutomated)
        assertFalse(controller.openHome())
    }

    @Test
    fun `result clears in flight and requests recomputation`() {
        val controller = MorningReviewEntryController()
        controller.onResume()
        controller.evaluate(availability(banner = true, catchUp = true))

        assertTrue(controller.onResult())
        assertTrue(controller.evaluate(availability(banner = true, catchUp = true)).launchAutomated)
    }

    @Test
    fun `home cancelled before eight preserves its visible banner`() {
        val controller = MorningReviewEntryController()
        controller.onResume()
        assertTrue(controller.openHome())

        assertTrue(controller.onResult())
        val decision = controller.evaluate(availability(banner = true, catchUp = false))
        assertTrue(decision.showHomeBanner)
        assertFalse(decision.launchAutomated)
    }

    @Test
    fun `foreground transitions honor midnight eight and snooze boundaries`() {
        val controller = MorningReviewEntryController()
        controller.onResume()

        assertTrue(controller.evaluate(availability(banner = true, catchUp = false)).showHomeBanner)
        assertTrue(controller.evaluate(availability(banner = true, catchUp = true)).launchAutomated)

        val snoozedController = MorningReviewEntryController()
        snoozedController.onResume()
        assertFalse(snoozedController.evaluate(availability(banner = false, catchUp = false, snoozed = true)).showHomeBanner)
        assertTrue(snoozedController.evaluate(availability(banner = true, catchUp = true)).launchAutomated)
    }

    private fun availability(
        banner: Boolean,
        catchUp: Boolean,
        snoozed: Boolean = false
    ) = MorningReviewAvailability(
        today = LocalDate.of(2026, 8, 8),
        bannerVisible = banner,
        catchUpRequired = catchUp,
        snoozedNow = snoozed,
        nextReevaluationAt = Instant.parse("2026-08-09T00:00:00Z")
    )
}
