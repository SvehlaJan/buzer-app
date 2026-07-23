package app.buzer.android.enforcement

enum class BlockerAction {
    START_TASK,
    POSTPONE_START,
    COMPLETE_TASK,
    EXTEND_TASK,
    UPDATE_TASK,
    CHECK_CHILD,
    UNCHECK_CHILD,
    MORNING_REVIEW_DISMISS,
    REFRESH_SNAPSHOT
}
