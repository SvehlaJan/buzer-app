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
