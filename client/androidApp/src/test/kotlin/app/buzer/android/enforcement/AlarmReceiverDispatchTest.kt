package app.buzer.android.enforcement

import app.buzer.android.morningreview.MorningReviewAlarmDecision
import kotlin.test.Test
import kotlin.test.assertEquals

class AlarmReceiverDispatchTest {
    @Test
    fun morningReviewLaunchInvokesHandlerBeforeOpeningBlocker() {
        val events = mutableListOf<String>()

        dispatchAlarmDelivery(
            kind = AlarmKind.MORNING_REVIEW,
            handleMorningReview = {
                events += "handler"
                MorningReviewAlarmDecision.Launch
            },
            showPreStart = { events += "pre-start" },
            openBlocker = { events += "open:$it" }
        )

        assertEquals(listOf("handler", "open:MORNING_REVIEW"), events)
    }

    @Test
    fun morningReviewDeferredOrIgnoredDoesNotOpenBlocker() {
        val events = mutableListOf<String>()

        listOf(MorningReviewAlarmDecision.Deferred, MorningReviewAlarmDecision.Ignore).forEach { decision ->
            dispatchAlarmDelivery(
                kind = AlarmKind.MORNING_REVIEW,
                handleMorningReview = {
                    events += "handler:$decision"
                    decision
                },
                showPreStart = { events += "pre-start" },
                openBlocker = { events += "open:$it" }
            )
        }

        assertEquals(listOf("handler:Deferred", "handler:Ignore"), events)
    }

    @Test
    fun preStartContinuesToNotificationPathWithoutMorningReviewHandler() {
        val events = mutableListOf<String>()

        dispatchAlarmDelivery(
            kind = AlarmKind.PRE_START_WARNING,
            handleMorningReview = { error("unexpected Morning Review handler") },
            showPreStart = { events += "pre-start" },
            openBlocker = { events += "open:$it" }
        )

        assertEquals(listOf("pre-start"), events)
    }

    @Test
    fun startAndDeadlineContinueToBlockerPathWithoutMorningReviewHandler() {
        val events = mutableListOf<String>()

        listOf(AlarmKind.START_BLOCKER, AlarmKind.DEADLINE_BLOCKER).forEach { kind ->
            dispatchAlarmDelivery(
                kind = kind,
                handleMorningReview = { error("unexpected Morning Review handler") },
                showPreStart = { events += "pre-start" },
                openBlocker = { events += "open:$it" }
            )
        }

        assertEquals(listOf("open:START_BLOCKER", "open:DEADLINE_BLOCKER"), events)
    }
}
