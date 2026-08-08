package app.buzer.android.morningreview

data class MorningReviewEntryDecision(
    val showHomeBanner: Boolean,
    val launchAutomated: Boolean
)

/** Keeps Home visibility independent from the one-shot automated entry guard. */
object MorningReviewEntryDecider {
    fun decide(
        availability: MorningReviewAvailability,
        launchInFlight: Boolean
    ): MorningReviewEntryDecision = MorningReviewEntryDecision(
        showHomeBanner = availability.bannerVisible,
        launchAutomated = availability.catchUpRequired && !launchInFlight
    )
}

/** Lifecycle gate for MainActivity's single launch authority and reevaluation job. */
class MorningReviewEntryController {
    var isForeground: Boolean = false
        private set
    private var launchInFlight = false

    fun onResume() {
        isForeground = true
    }

    fun onPause() {
        isForeground = false
    }

    /** The Activity Result callback always needs to refresh local availability. */
    fun onResult(): Boolean {
        launchInFlight = false
        return true
    }

    fun evaluate(availability: MorningReviewAvailability): MorningReviewEntryDecision {
        val decision = MorningReviewEntryDecider.decide(availability, launchInFlight)
        val launchAutomated = isForeground && decision.launchAutomated
        if (launchAutomated) launchInFlight = true
        return decision.copy(launchAutomated = launchAutomated)
    }

    fun openHome(): Boolean {
        if (!isForeground || launchInFlight) return false
        launchInFlight = true
        return true
    }
}
