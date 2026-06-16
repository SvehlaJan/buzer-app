package app.buzer.android.ui.status

sealed interface OperationStatus {
    data object Idle : OperationStatus
    data class Loading(val label: String) : OperationStatus
    data class Failed(val message: String) : OperationStatus

    val isInFlight: Boolean
        get() = this is Loading

    val loadingLabel: String?
        get() = (this as? Loading)?.label

    val errorMessage: String?
        get() = (this as? Failed)?.message
}

sealed interface ScreenLoadState<out T> {
    data object Loading : ScreenLoadState<Nothing>
    data class Ready<T>(val data: T, val warning: String? = null, val refreshing: Boolean = false) : ScreenLoadState<T>
    data class Failed(val message: String) : ScreenLoadState<Nothing>

    val isBlocking: Boolean
        get() = this is Loading || this is Failed
}
