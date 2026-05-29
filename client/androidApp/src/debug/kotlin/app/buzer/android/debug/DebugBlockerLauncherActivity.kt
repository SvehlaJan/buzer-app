package app.buzer.android.debug

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import app.buzer.android.blockers.DeadlineBlockerActivity
import app.buzer.android.blockers.MorningReviewActivity
import app.buzer.android.blockers.StartBlockerActivity
import app.buzer.android.enforcement.AlarmIntentFactory

class DebugBlockerLauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = when (intent.getStringExtra(EXTRA_BLOCKER_KIND)) {
            KIND_START -> StartBlockerActivity::class.java
            KIND_DEADLINE -> DeadlineBlockerActivity::class.java
            KIND_MORNING -> MorningReviewActivity::class.java
            else -> null
        }
        if (target != null) {
            startActivity(Intent(this, target).apply {
                intent.getStringExtra(AlarmIntentFactory.EXTRA_TASK_ID)?.let {
                    putExtra(AlarmIntentFactory.EXTRA_TASK_ID, it)
                }
            })
        }
        finish()
    }

    private companion object {
        const val EXTRA_BLOCKER_KIND = "app.buzer.debug.BLOCKER_KIND"
        const val KIND_START = "start"
        const val KIND_DEADLINE = "deadline"
        const val KIND_MORNING = "morning"
    }
}
