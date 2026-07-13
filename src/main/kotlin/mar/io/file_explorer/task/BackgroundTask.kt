package mar.io.file_explorer.task

/*
the idea:
A background task should provide content for a frame.
This frame should show information about the task:
- progress
- potential errors
The background task should, if possible, be cancellable.
Tasks can choose to pop-up their frame on occasions such as errors.
There should, however, be an option to prevent this pop-up behavior.
*/

/**
 * Represents a generic background task that does not block UI code while running.
 *
 * A background task should support some sort of progress indicator as well as content for a frame.
 */
interface BackgroundTask {
    /**
     * Run the task in a blocking manner. This method may throw at any time, but should provide a reason.
     *
     * A task is never expected to receive more than one call to this method as long as it represents its state
     * correctly.
     *
     * @param context The [context][BackgroundTaskContext] the task is now running in. A task can safely assume, that it
     * will always receive the same reference to a context object in this or other methods.
     * @throws Throwable To indicate that the task has failed.
     */
    suspend fun runTask(context: BackgroundTaskContext)

    fun getState(): State

    /**
     * The progress of the task. This method should return a value between `0` and `100`. This value should represent
     * the [state][State] of this task accurately (See [State] for more information).
     *
     * @return A [Float] in the range `[0; 100]`
     */
    fun getProgressPercent(): Float

    /** Cancel the task, doing any cleanup that is necessary before. */
    fun cancel(context: BackgroundTaskContext)

    enum class State {
        /**
         * The task has not yet been started. It may be run by calling [BackgroundTask.runTask]. The progress should be
         * `0`
         */
        INITIALIZED,

        /** The task is currently running. The progress may be anywhere in the allowed range. */
        RUNNING,

        /**
         * The task has failed. Progress should indicate what percentage of the task has been completed *successfully*.
         */
        FAILED,

        /**
         * The task was canceled by the caller. Progress should indicate what percentage of the task has been completed
         * *successfully*.
         */
        CANELED,

        /** The task has been completed *fully and successfully*. The progress should be `100`. */
        COMPLETED
    }
}