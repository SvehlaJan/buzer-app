package app.buzer.android

import android.app.Application
import app.buzer.android.observability.CurrentActivityTracker
import app.buzer.android.observability.ObservabilityRuntime

class BuzerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CurrentActivityTracker.install(this)
        ObservabilityRuntime.initialize(this)
    }
}
