package app.buzer.android.enforcement.overlay

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Supplies the owners normally provided by an Activity so a service-owned WindowManager overlay
 * can host Compose content.
 */
class OverlayComposeHost(context: Context) : LifecycleOwner, SavedStateRegistryOwner, OverlayHostHandle {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val view: ComposeView = ComposeView(context).apply {
        setViewTreeLifecycleOwner(this@OverlayComposeHost)
        setViewTreeSavedStateRegistryOwner(this@OverlayComposeHost)
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    init {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun setContent(content: @Composable () -> Unit) {
        view.setContent(content)
    }

    override fun markAttached() {
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    override fun destroy() {
        view.disposeComposition()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }
}
