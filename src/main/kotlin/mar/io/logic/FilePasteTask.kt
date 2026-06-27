package mar.io.logic

import kotlinx.coroutines.*
import mar.io.gui.FilePasteTaskGUI
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.io.path.isSameFileAs

/**
 * @param handler
 * @constructor
 */
class FilePasteTask(val controller: ExplorerController, val handler: FilePasteHandler) {
    private val scope = CoroutineScope(Dispatchers.Default)
    private var collisionContinuation: Continuation<FilePasteHandler.CollisionMode>? = null
    private var errorContinuation: Continuation<FilePasteHandler.ErrorSolution>? = null
    private var job: Job? = null
    private val guiDelegate = lazy { FilePasteTaskGUI(controller.frame, this) }
    private val gui: FilePasteTaskGUI by guiDelegate

    var isCancelled: Boolean = false
        private set
    var isDone: Boolean = false
        private set

    fun start() {
        if (job != null || isCancelled || isDone)
            return
        this.job = scope.launch {
            launch {
                handler.executeOps()
                // as long as there are failed operations, prompt the user
                while (!handler.isDone) {
                    for (failedOp in handler.getFailedOperations()) {
                        val errorType = failedOp.getErrorType()
                        if (errorType == FilePasteHandler.ErrorType.NONE) {
                            // should never happen
                            continue
                        }
                        if (errorType == FilePasteHandler.ErrorType.COLLISION) {
                            // prompt user for new collision mode
                            suspendCoroutine {
                                this@FilePasteTask.collisionContinuation = it
                                gui.setCollisionError(failedOp)
                            }.let { newMode ->
                                ensureActive()
                                failedOp.collisionMode = newMode
                                handler.executeOps()
                            }
                        } else if (errorType == FilePasteHandler.ErrorType.GENERAL) {
                            // prompt user for new error solution
                            suspendCoroutine {
                                this@FilePasteTask.errorContinuation = it
                                gui.setGeneralError(failedOp)
                            }.let { newSolution ->
                                ensureActive()
                                failedOp.errorSolution = newSolution
                                handler.executeOps()
                            }
                        }
                    }
                }
            }
            // TODO: timeout?
        }
        job?.invokeOnCompletion { throwable ->
            if (throwable == null && !isCancelled) {
                this@FilePasteTask.isDone = true
            }
            controller.reloadFileList(true, false)
            if (controller.currentDir().isSameFileAs(handler.destinationDir)) {
                val pastedFiles = handler.getSuccessfulOperations().map { it.actualTarget }
                controller.gui.trySelectFiles(pastedFiles)
            }
            gui.destroy()
        }
    }

    fun resumeAfterCollision(mode: FilePasteHandler.CollisionMode) {
        this.collisionContinuation?.resume(mode)
        this.collisionContinuation = null
        gui.removeError()
    }

    fun resumeAfterGeneralError(solution: FilePasteHandler.ErrorSolution) {
        this.errorContinuation?.resume(solution)
        this.errorContinuation = null
        gui.removeError()
    }

    fun cancel() {
        if (isCancelled || isDone)
            return
        isCancelled = true
        this.job?.cancel()
        if (!guiDelegate.isInitialized())
            return
        gui.destroy()
    }
}