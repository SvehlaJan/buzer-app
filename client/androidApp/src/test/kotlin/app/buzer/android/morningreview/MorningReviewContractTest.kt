package app.buzer.android.morningreview

import android.content.Intent
import kotlin.test.Test
import kotlin.test.assertEquals

class MorningReviewContractTest {
    @Test
    fun homeAndAutomatedFactoriesRoundTripTheirSources() {
        assertEquals(
            MorningReviewEntrySource.Home,
            MorningReviewContract.entrySource(MorningReviewContract.homeIntent(FakeIntent()))
        )
        assertEquals(
            MorningReviewEntrySource.Automated,
            MorningReviewContract.entrySource(MorningReviewContract.automatedIntent(FakeIntent()))
        )
    }

    @Test
    fun missingOrUnknownSourceDefaultsToAutomatedForBlockerSafety() {
        assertEquals(MorningReviewEntrySource.Automated, MorningReviewContract.entrySource(FakeIntent()))
        assertEquals(
            MorningReviewEntrySource.Automated,
            MorningReviewContract.entrySource(FakeIntent().putExtra(MorningReviewContract.EXTRA_ENTRY_SOURCE, "unknown"))
        )
    }

    @Test
    fun resultExtrasRoundTripEveryTerminalOutcome() {
        MorningReviewResult.entries.forEach { expected ->
            assertEquals(
                expected,
                MorningReviewContract.result(MorningReviewContract.resultIntent(expected, FakeIntent()))
            )
        }
    }

    private class FakeIntent : Intent() {
        private val extras = mutableMapOf<String, String>()

        override fun putExtra(name: String?, value: String?): Intent {
            if (name != null && value != null) extras[name] = value
            return this
        }

        override fun getStringExtra(name: String?): String? = extras[name]
    }
}
