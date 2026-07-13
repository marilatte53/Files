package mar.io.file_explorer.task

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class BackgroundTaskManager {
    val tasks: MutableList<BackgroundTaskContext> = mutableListOf()
    protected val scope = CoroutineScope(Dispatchers.Default)

    fun addAndStartTask(task: BackgroundTask) {
        val ctx = BackgroundTaskContext(scope, task)
        tasks.add(ctx)
        ctx.startTask()
    }
}

class BackgroundTaskContext(
    val scope: CoroutineScope,
    val task: BackgroundTask
) {
    lateinit var job: Job

    fun startTask() {
        this.job = scope.launch {
            task.runTask(this@BackgroundTaskContext)
        }
    }
}