package mar.io.logic

import mar.io.logic.ExplorerController
import java.nio.file.Path
import kotlin.io.path.*

/**
 * Used to handle an operation that pastes every file or directory in [sourceFiles] into [destinationDir]. Optionally,
 * the source files can be deleted at the end.
 *
 * @param sourceFiles The files and directories to copy. Duplicates will be ignored and only be copied once.
 * @param deleteSourceFiles Whether to delete the source files after the target files have been created.
 * @param defaultCollisionMode This is used per default for all pasted files. In case this value is
 * [PasteCollisionMode.RESOLVE_LATER], the collision mode can later be set for each file individually using
 * [FilePasteOperation.collisionMode]
 */
class FileListPasteOperation(
    val controller: ExplorerController,
    sourceFiles: List<Path>,
    val destinationDir: Path,
    val deleteSourceFiles: Boolean,
    var defaultCollisionMode: PasteCollisionMode
) {
    private val operations: List<FilePasteOperation>

    /**
     * Whether the operation is done. Regardless of the return value, this does not mean that the entire operation has
     * finished successfully.
     */
    var isDone: Boolean = false
        private set
    var isCancelled: Boolean = false
        private set

    /** Creates and immediately attempts to execute a [mar.io.logic.controller.FileListPasteOperation]. */
    init {
        if (sourceFiles.isEmpty())
            throw IllegalStateException("Source file list is empty.")
        if (!destinationDir.exists())
            throw IllegalStateException("Destination directory does not exist")
        if (!destinationDir.isDirectory())
            throw IllegalStateException("Destination is not a directory")
        // associate creates a copy to make the resulting map immutable
        this.operations = sourceFiles.also { srcFile ->
            srcFile.forEach { src ->
                if (src.isDirectory()) {
                    /** check if the target is inside the source directory; stolen from [Path.copyToRecursively] */
                    val isSubdirectory: Boolean = when {
                        src.fileSystem != destinationDir.fileSystem -> false

                        destinationDir.exists() && !destinationDir.isSymbolicLink() -> destinationDir.toRealPath()
                            .startsWith(src.toRealPath())

                        else ->
                            destinationDir.parent?.let { it.exists() && it.toRealPath().startsWith(src.toRealPath()) }
                                ?: false
                    }
                    if (isSubdirectory)
                        throw FileSystemException(
                            src.toRealPath().toFile(),
                            destinationDir.toRealPath().toFile(),
                            "Recursively copying a directory into its subdirectory is prohibited."
                        )
                }
            }
        }.map { FilePasteOperation(it) }
    }

    fun execute() {
        if (isDone || isCancelled)
            return
        this.isDone = operations.all { op ->
            op.tryExecute()
            return@all op.isDone()
        }
    }

    fun srcFileSize() = operations.size

    fun getFailedOperations(): List<FilePasteOperation> {
        return operations.filter { it.didFail() }
    }

    fun cancel() {
        isCancelled = true
    }

    @OptIn(ExperimentalPathApi::class)
    inner class FilePasteOperation(val srcFile: Path) {
        val originalTarget: Path

        /** Used when collisionMode is [PasteCollisionMode.CREATE_SIBLING]. */
        var actualTarget: Path
            private set
        private var state: PasteOpState = PasteOpState.INIT
        private var errorState: PasteOpErrorState = PasteOpErrorState.NONE
        var errorSolution: PasteOpErrorSolution = PasteOpErrorSolution.NONE
            /**
             * Set the solution that should be used to resolve the current error the next time
             * [mar.io.logic.controller.FileListPasteOperation.execute] is called. If there has been no error recorded, this
             * function does nothing.
             */
            set(value) {
                if (!didFail())
                    return
                field = value
            }
        var throwable: Throwable? = null
            private set

        /** The collision mode that was last used in the resolution attempt of this collision. */
        var collisionModeUsed: PasteCollisionMode? = null
            private set

        /** Can be used to override [mar.io.logic.controller.FileListPasteOperation.defaultCollisionMode]. */
        var collisionMode: PasteCollisionMode? = null

        init {
            this.originalTarget = destinationDir.resolve(srcFile.name)
            this.actualTarget = originalTarget
        }

        fun getErrorType(): PasteOpErrorType {
            if (!didFail())
                return PasteOpErrorType.NONE
            if (state == PasteOpState.COLLISION_DETECTED)
                return PasteOpErrorType.COLLISION
            return PasteOpErrorType.GENERAL
        }

        private fun effectiveCollisionMode() = collisionMode ?: defaultCollisionMode

        private fun checkForCollision() {
            if (actualTarget.exists()) {
                this.state = PasteOpState.COLLISION_DETECTED
                return
            }
            this.state = PasteOpState.COLLISION_RESOLVED
        }

        private fun executeUnsafe() {
            if (!srcFile.exists()) {
                throw NoSuchFileException(srcFile.toRealPath().toFile(), reason = "Source file does not exist")
            }
            // If the collision resolution failed previously, check if the collision still exists
            if (state == PasteOpState.INIT || (state == PasteOpState.COLLISION_DETECTED && didFail())) {
                checkForCollision()
            }
            if (state == PasteOpState.COLLISION_DETECTED) {
                // try to resolve the collision
                try {
                    tryResolveCollision(effectiveCollisionMode())
                } catch (t: Throwable) {
                    throw PasteCollisionException(
                        srcFile, originalTarget, actualTarget,
                        "Error while resolving paste collision."
                    ).initCause(t)
                }
            }
            if (state == PasteOpState.COLLISION_RESOLVED) {
                // now we try to paste the file in the target location
                /*
                Due to file system concurrency, it is possible to encounter further collisions here. 
                If there is a simple file collision, the function will throw, which is desired here.
                If there is a directory collision, the function will perform a directory merge operation. This operation
                is not desired, but it shouldn't break or delete anything so it's fine.
                 */
                srcFile.copyToRecursively(actualTarget, onError = { src, target, exception ->
                    /* 
                    FileAlreadyExistException here means a collision has happened because a file was created after the
                    above collision check
                    */
                    throw exception
                }, followLinks = false)
                // TODO: overwrite behavior, directory merge
                this.state = PasteOpState.TARGET_PASTED
            }
            if (state == PasteOpState.TARGET_PASTED) {
                if (!deleteSourceFiles)
                    return
                this.state = PasteOpState.DONE
                errorState = PasteOpErrorState.NONE
                // TODO: delete source (verify that the target was pasted)
            }
        }

        /**
         * For this function to do something, [FilePasteOperation.state] must be equal to
         * [PasteOpState.COLLISION_DETECTED].
         *
         * @throws PasteCollisionException
         */
        private fun tryResolveCollision(collisionMode: PasteCollisionMode) {
            if (state != PasteOpState.COLLISION_DETECTED)
                return
            this.collisionModeUsed = collisionMode
            when (collisionMode) {
                PasteCollisionMode.CREATE_SIBLING -> {
                    var copies = 0
                    while (actualTarget.exists()) { // TODO: safe-guard: limit amount of copies or smth
                        actualTarget = originalTarget.resolveSibling(
                            "${originalTarget.nameWithoutExtension}" +
                                    "_copy${copies++}.${originalTarget.extension}"
                        )
                    }
                }
                // TODO: overwrite mode; directory merge mode
                PasteCollisionMode.MARK_RESOLVED -> {
                    this@FilePasteOperation.state = PasteOpState.DONE
                    return
                }

                PasteCollisionMode.RESOLVE_LATER -> {
                    this@FilePasteOperation.errorState = PasteOpErrorState.ERROR
                    return
                }
            }
            this@FilePasteOperation.state = PasteOpState.COLLISION_RESOLVED
        }

        /** If the operation for this file is already done, does nothing. */
        internal fun tryExecute() {
            if (this.isDone())
                return
            if (didFail()) {
                // try to resolve the error
                if (errorSolution != PasteOpErrorSolution.RETRY) {
                    if (errorSolution == PasteOpErrorSolution.CANCEL) {
                        this@FileListPasteOperation.cancel()
                    }
                    if (errorSolution == PasteOpErrorSolution.SKIP) {
                        this@FilePasteOperation.state = PasteOpState.DONE
                    }
                    // else if errorSolution == PasteOpErrorSolution.NONE
                    return
                }
                // errorSolution == RETRY
                // reset the solution in case of a new error
                errorSolution == PasteOpErrorSolution.NONE
            }
            try {
                executeUnsafe()
            } catch (t: Throwable) {
                this.errorState = PasteOpErrorState.ERROR
                this.throwable = t
            }
        }

        fun didCollide() = state == PasteOpState.COLLISION_DETECTED
        fun isDone() = state == PasteOpState.DONE
        fun didFail() = errorState != PasteOpErrorState.NONE
    }

    class PasteCollisionException(src: Path, target: Path, val actualTarget: Path, message: String) :
        FileSystemException(
            src.toRealPath().toFile(), target.toRealPath().toFile(),
            "(${actualTarget.toRealPath().invariantSeparatorsPathString}) " + message
        )

    /** Defines how paste collisions should be handled in a [mar.io.logic.controller.FileListPasteOperation] */
    enum class PasteCollisionMode {
        /** Paste the source file as a sibling to the target file with a related name. */
        CREATE_SIBLING,

        /** Do nothing and let the caller decide, when and how to handle the collision. */
        RESOLVE_LATER,

        /**
         * Do nothing but mark the collision as resolved. Allows the operation to finish without actually resolving the
         * collision.
         */
        MARK_RESOLVED
    }

    private enum class PasteOpState {
        INIT,
        COLLISION_DETECTED,
        COLLISION_RESOLVED,

        /**
         * The target file has been successfully pasted, but the possible source file deletion has not been handled yet.
         */
        TARGET_PASTED,
        DONE
    }

    private enum class PasteOpErrorState {
        NONE, ERROR
    }

    enum class PasteOpErrorSolution {
        NONE, SKIP, RETRY, CANCEL
    }

    enum class PasteOpErrorType {
        NONE, COLLISION, GENERAL
    }
}
