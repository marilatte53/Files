package mar.io.file_explorer.task

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Convenience class to provide a basic structure for [BackgroundTask]s. Also provides CoroutineContext directly to the
 * runTask method. This can be used by overriding [runCoroutineTask].
 */
abstract class CoroutineBackgroundTask : BackgroundTask {
    protected var state: BackgroundTask.State = BackgroundTask.State.INITIALIZED
    protected lateinit var context: BackgroundTaskContext

    override fun getState(): BackgroundTask.State {
        return state
    }

    override fun cancel(context: BackgroundTaskContext) {
        this.context.job.cancel()
        this.state = BackgroundTask.State.CANELED
    }

    override suspend fun runTask(context: BackgroundTaskContext) {
        this.context = context
        this.state = BackgroundTask.State.RUNNING
        with(context.scope) { this@with.runCoroutineTask(context) }
    }

    abstract suspend fun CoroutineScope.runCoroutineTask(context: BackgroundTaskContext)
}