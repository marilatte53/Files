package mar.io.logic

import java.nio.file.Path
import kotlin.io.path.*

/**
 * Used to paste a list of files into a new directory
 *
 * @param sourceFiles The files and directories to copy. Duplicates will be ignored and only be copied once.
 * @param shouldDeleteSrc Whether to delete the source files after the target files have been created.
 * @param defaultCollisionMode This is used per default for all pasted files. In case this value
 * is [CollisionMode.RETRY], the collision mode can later be set for each file individually using
 * [PasteOperation.collisionMode]
 */
class FilePasteHandler(
    sourceFiles: List<Path>,
    val destinationDir: Path,
    val shouldDeleteSrc: Boolean,
    var defaultCollisionMode: CollisionMode
) {
    private val operations: List<PasteOperation>

    /**
     * Whether the operation is done. Regardless of the return value, this does not mean that the entire operation has
     * finished successfully.
     */
    var isDone: Boolean = false
        private set

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
        }.map { PasteOperation(it) }
    }

    fun executeOps() {
        if (isDone)
            return
        this.isDone = operations.all { op ->
            op.tryExecute()
            return@all op.isDone()
        }
    }

    /** The number of individual [PasteOperation]s. Equals the number of source files passed in the constructor. */
    fun fileCount() = operations.size

    fun getFailedOperations(): List<PasteOperation> {
        return operations.filter { it.didFail() }
    }

    fun getSuccessfulOperations(): List<PasteOperation> {
        return operations.filter { it.isDone() }
    }

    @OptIn(ExperimentalPathApi::class)
    inner class PasteOperation(val srcFile: Path) {
        val originalTarget: Path

        private var state: State = State.INIT
        private var errorState: ErrorState = ErrorState.NONE

        /** Used when collisionMode is [CollisionMode.CREATE_SIBLING]. */
        var actualTarget: Path
            private set
        var throwable: Throwable? = null
            private set

        /** The collision mode that was last used in the resolution attempt of this collision. */
        var collisionModeUsed: CollisionMode? = null
            private set

        var errorSolution: ErrorSolution = ErrorSolution.RESOLVE_LATER
            /**
             * Set the solution that should be used to resolve the current error the next time
             * [FilePasteHandler.executeOps] is called. If there has been no error recorded, this function does nothing.
             */
            set(value) {
                if (!didFail())
                    return
                field = value
            }

        /** Can be used to override [FilePasteHandler.defaultCollisionMode]. */
        var collisionMode: CollisionMode? = null

        init {
            this.originalTarget = destinationDir.resolve(srcFile.name)
            this.actualTarget = originalTarget
        }

        fun getErrorType(): ErrorType {
            if (!didFail())
                return ErrorType.NONE
            if (state == State.COLLISION_DETECTED)
                return ErrorType.COLLISION
            return ErrorType.GENERAL
        }

        private fun effectiveCollisionMode() = collisionMode ?: defaultCollisionMode

        private fun checkForCollision() {
            if (!srcFile.exists()) {
                throw NoSuchFileException(srcFile.toRealPath().toFile(), reason = "Source file does not exist")
            }
            if (actualTarget.exists()) {
                this.state = State.COLLISION_DETECTED
                return
            }
            this.state = State.COLLISION_RESOLVED
        }

        /**
         * For this function to do something, [PasteOperation.state] must be equal to [State.COLLISION_DETECTED].
         *
         * @throws PasteCollisionException
         */
        private fun tryResolveCollision(collisionMode: CollisionMode) {
            if (state != State.COLLISION_DETECTED)
                return
            this.collisionModeUsed = collisionMode
            when (collisionMode) {
                CollisionMode.CREATE_SIBLING -> {
                    var copies = 1
                    while (actualTarget.exists()) { // TODO: safe-guard: limit amount of copies or smth
                        actualTarget = originalTarget.resolveSibling(
                            "${originalTarget.nameWithoutExtension}" +
                                    "_copy${copies++}.${originalTarget.extension}"
                        )
                    }
                }
                // TODO: overwrite mode; directory merge mode
                CollisionMode.MARK_RESOLVED -> {
                    this@PasteOperation.state = State.DONE
                    return
                }

                CollisionMode.RETRY -> {
                    this@PasteOperation.errorState = ErrorState.ERROR
                    return
                }
            }
            this@PasteOperation.state = State.COLLISION_RESOLVED
        }

        private fun executeUnsafe() {
            // If the collision resolution failed previously, check if the collision still exists
            if (state == State.INIT || getErrorType() == ErrorType.COLLISION) {
                checkForCollision()
                errorState = ErrorState.NONE
            }
            if (state == State.COLLISION_DETECTED) {
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
            if (state == State.COLLISION_RESOLVED) {
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
                this.state = State.TARGET_PASTED
            }
            if (state == State.TARGET_PASTED) {
                if (!shouldDeleteSrc) {
                    this.state = State.DONE
                }
                // TODO: delete source (verify that the target was pasted)
            }
        }

        /** If the operation for this file is already done, does nothing. */
        internal fun tryExecute() {
            if (this.isDone())
                return
            if (didFail() && getErrorType() == ErrorType.GENERAL) {
                // try to resolve the error
                if (errorSolution != ErrorSolution.RETRY) {
                    if (errorSolution == ErrorSolution.MARK_RESOLVED) {
                        this@PasteOperation.state = State.DONE
                    }
                    // else if errorSolution == PasteOpErrorSolution.NONE
                    return
                }
                // errorSolution == RETRY
                errorState = ErrorState.NONE
                throwable = null
                // reset in case of a new error
                errorSolution = ErrorSolution.RESOLVE_LATER
            }
            try {
                executeUnsafe()
            } catch (t: Throwable) {
                this.errorState = ErrorState.ERROR
                this.throwable = t
            }
        }

        fun didCollide() = state == State.COLLISION_DETECTED
        fun isDone() = state == State.DONE
        fun didFail() = errorState != ErrorState.NONE
    }

    class PasteCollisionException(src: Path, target: Path, val actualTarget: Path, message: String) :
        FileSystemException(
            src.toRealPath().toFile(), target.toRealPath().toFile(),
            "(${actualTarget.toRealPath().invariantSeparatorsPathString}) " + message
        )

    private enum class State {
        INIT,
        COLLISION_DETECTED,
        COLLISION_RESOLVED,

        /**
         * The target file has been successfully pasted, but the possible source file deletion has not been handled yet.
         */
        TARGET_PASTED,
        DONE
    }

    private enum class ErrorState {
        NONE, ERROR
    }

    /** Defines how paste collisions should be handled in a [FilePasteHandler] */
    enum class CollisionMode {
        /** Paste the source file as a sibling to the target file with a related name. */
        CREATE_SIBLING,

        /**
         * Do nothing now. The collision will be checked again the next time the op is executed. In the meantime, the
         * caller may choose to change the collision mode.
         */
        RETRY,

        /**
         * Do nothing but mark the collision as resolved. Allows the operation to finish without actually resolving the
         * collision.
         */
        MARK_RESOLVED
    }

    enum class ErrorSolution {
        RESOLVE_LATER, MARK_RESOLVED, RETRY
    }

    enum class ErrorType {
        NONE, COLLISION, GENERAL
    }
}
