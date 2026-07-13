package mar.io.file_explorer.task.file_paste

import kotlinx.coroutines.*
import mar.io.file_explorer.base_logic.ExplorerController
import mar.io.file_explorer.task.BackgroundTaskContext
import mar.io.file_explorer.task.CoroutineBackgroundTask
import javax.swing.SwingUtilities
import kotlin.coroutines.resume
import kotlin.io.path.isSameFileAs

/**
 * @param handler
 * @constructor
 */
class FilePasteBackgroundTask(val controller: ExplorerController, val handler: FilePasteManger) :
    CoroutineBackgroundTask() {
    protected val guiDelegate = lazy { FilePasteTaskGUI(controller.frame, this) }
    protected val gui: FilePasteTaskGUI by guiDelegate

    /** Indicates that collision- or error-strategies were set by the GUI and should now be used by this task. */
    protected var dirty: Boolean = false

    // curseeeeeeeeeed
    protected var continuation: CancellableContinuation<Nothing?>? = null

    init {
        // Forward all detected collisions and errors to the GUI
        handler.incompletableOperationCallback = gui::addIncompleteOperation
    }

    override suspend fun CoroutineScope.runCoroutineTask(context: BackgroundTaskContext) {
        do {
            // Execute all remaining operations
            handler.executeIncompleteOperations()
            // Wait for any GUI notifications
            suspendCancellableCoroutine {
                this@FilePasteBackgroundTask.continuation = it
            }
        } while (!handler.isCompleted)
        // as long as there are failed operations, prompt the user
        while (!handler.isCompleted) {
            // TODO: Re-do this part and the GUI
            // The task should immediately report detected collisions to the GUI while running and notify the GUI.
            // After completing a full run, it should wait until the GUI reports any changes.
            // The GUI can report changes during a run already.
            for (incompleteOp in handler.getIncompleteOperations()) {
                if (incompleteOp.hasUnresolvedCollision()) {
                    // This prompts the user and then waits for a callback from the continuation object
                    suspendCancellableCoroutine {
                        this@FilePasteBackgroundTask.collisionContinuation = it
                        gui.promptUserForCollision(incompleteOp)
                    }.let { newMode ->
                        ensureActive()
                        incompleteOp.collisionStrategy = newMode
                        handler.executeIncompleteOperations()
                    }
                } else if (incompleteOp.hasUnresolvedError()) {
                    // prompt user for new error solution
                    suspendCancellableCoroutine {
                        this@FilePasteBackgroundTask.errorContinuation = it
                        gui.promptUserForError(incompleteOp)
                    }.let { newStrategy ->
                        ensureActive()
                        incompleteOp.errorStrategy = newStrategy
                        handler.executeIncompleteOperations()
                    }
                }
            }
        }
        // TODO: When the program is closed, cancel all the pending tasks.
        job?.invokeOnCompletion { throwable ->
            // TODO: notify the user if the task is incomplete
            if (throwable == null && !isCancelled) {
                this@FilePasteBackgroundTask.isDone = true
            }
            SwingUtilities.invokeLater {
                // TODO: reload the file list after each individual paste op when user is in that dir.
                // TODO: figure out, when to select the pasted files.
                if (controller.currentDir().isSameFileAs(handler.destinationDir)) {
                    controller.reloadFileList(true, false)
                    val pastedFiles = handler.getCompletedOperations().map { it.actualTarget }
                    controller.gui.trySelectFiles(pastedFiles)
                }
                gui.destroy()
            }
        }
    }

    fun setCollisionStrategy(mode: FilePasteManger.CollisionStrategy) {
        this.collisionContinuation?.resume(mode)
        this.collisionContinuation = null
        gui.removeError()
    }

    fun resumeAfterGeneralError(solution: FilePasteManger.ErrorStrategy) {
        this.errorContinuation?.resume(solution)
        this.errorContinuation = null
        gui.removeError()
    }

    override fun getProgressPercent(): Float {
        return handler.getIncompleteOperations().size.toFloat() / handler.fileCount()
    }
}