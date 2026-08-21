package app.buzer.android.observability

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

object CurrentActivityTracker : Application.ActivityLifecycleCallbacks {
    @Volatile
    private var currentActivityRef: WeakReference<Activity>? = null

    val currentActivity: Activity?
        get() {
            val activity = currentActivityRef?.get() ?: return null
            if (activity.isFinishing || activity.isDestroyed) return null
            return activity
        }

    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        currentActivityRef = WeakReference(activity)
    }

    override fun onActivityResumed(activity: Activity) {
        currentActivityRef = WeakReference(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        // Retain current activity until another starts or this one is stopped/destroyed
    }

    override fun onActivityStopped(activity: Activity) {
        if (currentActivityRef?.get() === activity) {
            if (activity.isFinishing || activity.isDestroyed) {
                currentActivityRef = null
            }
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (currentActivityRef?.get() === activity) {
            currentActivityRef = null
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    internal fun setActivityForTest(activity: Activity?) {
        currentActivityRef = activity?.let { WeakReference(it) }
    }
}
