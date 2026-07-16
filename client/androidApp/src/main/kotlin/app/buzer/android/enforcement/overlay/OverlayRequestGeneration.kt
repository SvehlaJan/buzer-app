package app.buzer.android.enforcement.overlay

/** Monotonic ownership token so an older request cannot tear down a newer overlay. */
internal class OverlayRequestGeneration {
    private var current = 0L

    fun next(): Long = ++current

    fun isCurrent(generation: Long): Boolean = generation == current
}
