package app.sanafi.assistant.call

/** What the call screen shows. */
sealed interface CallUi {
    data object Idle : CallUi
    data object Connecting : CallUi
    data object Listening : CallUi
    data object Thinking : CallUi
    data object Speaking : CallUi
    data class Failed(val message: String) : CallUi
}
