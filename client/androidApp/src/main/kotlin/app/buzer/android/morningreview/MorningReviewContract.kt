package app.buzer.android.morningreview

import android.content.Intent

enum class MorningReviewEntrySource { Home, Automated }

enum class MorningReviewResult { Acknowledged, Snoozed, Cancelled }

/** Stable intent and result boundary for the two Morning Review entry surfaces. */
object MorningReviewContract {
    const val EXTRA_ENTRY_SOURCE = "app.buzer.extra.MORNING_REVIEW_ENTRY_SOURCE"
    const val EXTRA_RESULT = "app.buzer.extra.MORNING_REVIEW_RESULT"

    fun homeIntent(intent: Intent = Intent()): Intent =
        intent.putExtra(EXTRA_ENTRY_SOURCE, MorningReviewEntrySource.Home.name)

    fun automatedIntent(intent: Intent = Intent()): Intent =
        intent.putExtra(EXTRA_ENTRY_SOURCE, MorningReviewEntrySource.Automated.name)

    fun entrySource(intent: Intent): MorningReviewEntrySource =
        intent.getStringExtra(EXTRA_ENTRY_SOURCE)
            ?.let { source -> MorningReviewEntrySource.entries.firstOrNull { it.name == source } }
            ?: MorningReviewEntrySource.Automated

    fun resultIntent(result: MorningReviewResult, intent: Intent = Intent()): Intent =
        intent.putExtra(EXTRA_RESULT, result.name)

    fun result(intent: Intent?): MorningReviewResult? =
        intent?.getStringExtra(EXTRA_RESULT)
            ?.let { value -> MorningReviewResult.entries.firstOrNull { it.name == value } }
}
