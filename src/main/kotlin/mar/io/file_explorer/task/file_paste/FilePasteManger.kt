package mar.io.file_explorer.task.file_paste

import com.sun.jna.platform.FileUtils
import java.nio.file.Path
import kotlin.io.path.*

/**
 * Used to paste a list of files into a new directory
 *
 * @param sourceFiles The files and directories to copy. Duplicates will be ignored and only be copied once.
 * @param shouldDeleteSrc Whether to delete the source files after the target files have been created.
 * @param defaultCollisionStrategy This is used per default for all pasted files. In case this value is
 * [CollisionStrategy.RETRY], the collision mode can later be set for each file individually by setting
 * [the strategy][PasteOperation.collisionStrategy] for individual [operations][PasteOperation]
 */
class FilePasteManger(
    sourceFiles: List<Path>,
    val destinationDir: Path,
    val shouldDeleteSrc: Boolean,
    var defaultCollisionStrategy: CollisionStrategy
) {
    /*
    Paths are treated as-is and never converted to absolute or real paths UNLESS we actually access the corresponding file in the file system.
     */
    val operations: List<PasteOperation>

    /** Indicates that the operation has finished successfully. */
    var isCompleted: Boolean = false
        private set

    /** Reports operations, that cannot be completed due to collisions or errors */
    var incompletableOperationCallback: ((incompletableOp: PasteOperation) -> Unit)? = null

    init {
        if (sourceFiles.isEmpty())
            throw IllegalStateException("Source file list is empty.")
        /*
         We check the destination directory here, since it comes from our own code and as such is very likely to pass these checks.
         Source files are only checked when we actually need to access them later.
         */
        if (!destinationDir.exists())
            throw IllegalStateException("Destination directory does not exist")
        if (!destinationDir.isDirectory())
            throw IllegalStateException("Destination is not a directory")
        this.operations = sourceFiles.also { srcFile ->
            srcFile.forEach { src ->
                if (!src.isAbsolute) {
                    println("WARNING: Source file '${src.invariantSeparatorsPathString}' in paste operation is not absolute!")
                }
                if (src.isDirectory()) {
                    /** check if the target is inside the source directory; stolen from [copyToRecursively] */
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
                            src.toFile(),
                            destinationDir.toFile(),
                            "Recursively copying a directory into its subdirectory is prohibited."
                        )
                }
            }
        }.map { PasteOperation(it) }
    }

    fun executeIncompleteOperations() {
        if (isCompleted)
            return
        this.isCompleted = operations.all { op ->
            op.executeIfIncomplete()
            return@all op.isCompleted()
        }
    }

    /** The number of individual [PasteOperation]s. Equals the number of source files passed in the constructor. */
    fun fileCount() = operations.size

    /**
     * Returns all [PasteOperation]s that did not finish successfully. This can happen if a collision or error is not
     * resolved. Specifically, this includes all operations where [PasteOperation.isCompleted] == false.
     */
    fun getIncompleteOperations(): List<PasteOperation> {
        return operations.filter { !it.isCompleted() }
    }

    fun getCompletedOperations(): List<PasteOperation> {
        return operations.filter { it.isCompleted() }
    }

    @OptIn(ExperimentalPathApi::class)
    inner class PasteOperation(val srcFile: Path) {
        val originalTarget: Path

        private var state: State = State.INIT
        private var errorState: ErrorState = ErrorState.NONE

        /** Used when collisionMode is [CollisionStrategy.CREATE_SIBLING]. */
        var actualTarget: Path
            private set
        var throwable: Throwable? = null
            private set

        /** The collision mode that was last used in the resolution attempt of this collision. */
        var collisionStrategyUsed: CollisionStrategy? = null
            private set

        /**
         * Defines the strategy that should be used to resolve the current error the next time this operation is
         * executed. Should only be called when [PasteOperation.hasUnresolvedError] == true
         */
        var errorStrategy: ErrorStrategy = ErrorStrategy.RESOLVE_LATER

        /** Can be used to override [FilePasteManger.defaultCollisionStrategy]. */
        var collisionStrategy: CollisionStrategy = defaultCollisionStrategy

        init {
            this.originalTarget = destinationDir.resolve(srcFile.name)
            this.actualTarget = originalTarget
        }

        /** Tries to resolve the collision with the given [collisionStrategy]. */
        private fun resolveCollisionUnsafe(collisionStrategy: CollisionStrategy) {
            this.collisionStrategyUsed = collisionStrategy
            when (collisionStrategy) {
                CollisionStrategy.CREATE_SIBLING -> {
                    var copies = 1
                    while (actualTarget.exists()) {
                        actualTarget = originalTarget.resolveSibling(
                            "${originalTarget.nameWithoutExtension}_copy${copies++}.${originalTarget.extension}"
                        )
                    }
                    this@PasteOperation.state = State.COLLISION_RESOLVED
                }
                // TODO: implement OVERWRITE collision mode
                CollisionStrategy.MARK_RESOLVED -> {
                    this@PasteOperation.state = State.COMPLETED
                }

                CollisionStrategy.RETRY -> {
                }
            }
        }

        /** Execute the operation, throwing in case of an error. */
        private fun executeUnsafe() {
            /* 
            Check for collisions:
            - initially
            - when there has been a collision that has not yet been resolved
             */
            if (state == State.INIT || state == State.COLLISION_DETECTED) {
                // actual collision check
                if (actualTarget.exists()) {
                    this.state = State.COLLISION_DETECTED
                } else {
                    this.state = State.COLLISION_RESOLVED
                }
            }
            if (state == State.COLLISION_DETECTED) {
                // try to resolve the collision
                val strat = collisionStrategy
                try {
                    resolveCollisionUnsafe(strat)
                } catch (t: Throwable) {
                    throwEx("Failed to resolve paste collision (strategy: $strat).", t)
                }
            }
            if (state == State.COLLISION_RESOLVED) {
                try {
                    /* 
                   If the target file is a directory and a collision happens while calling the following function,
                   it will merge those two directories, which is not desired.
                   However, since we have resolved possible collisions here, this should not happen.
                 */
                    srcFile.copyToRecursively(
                        actualTarget,
                        onError = { src, target, exception -> throw exception },
                        followLinks = false
                    )
                } catch (t: Throwable) {
                    throwEx("Failed to copy file to target location.", t)
                }
                this.state = State.TARGET_PASTED
            }
            if (state == State.TARGET_PASTED) {
                if (actualTarget.notExists()) {
                    throwEx("Failed to find target file after successful copy operation.")
                }
                if (shouldDeleteSrc) {
                    val fileUtils = FileUtils.getInstance()
                    // TODO: Once we implement a custom recycle bin to make a history possible, we need to change this check
                    if (!fileUtils.hasTrash()) {
                        throwEx("Refusing to delete source file of cut-paste operation because Recycle Bin is not supported.")
                    }
                    fileUtils.moveToTrash(srcFile.toFile())
                    this.state = State.COMPLETED
                } else {
                    this.state = State.COMPLETED
                }
            }
        }

        /** Execute the operation if it is not yet completed. This method should not throw. */
        internal fun executeIfIncomplete() {
            if (this.isCompleted())
                return
            if (hasUnresolvedError()) {
                // Handle the error according to the strategy
                if (errorStrategy != ErrorStrategy.RETRY) {
                    if (errorStrategy == ErrorStrategy.MARK_COMPLETED) {
                        this@PasteOperation.state = State.COMPLETED
                    }
                    return
                }
                /*
                errorSolution == RETRY
                Ignore the last error, simply try again
                Reset the error strategy, otherwise we would cause an infinite loop as long as errors keep happening.
                 */
                this.errorState = ErrorState.NONE
                this.throwable = null
                this.errorStrategy = ErrorStrategy.RESOLVE_LATER
            }
            try {
                executeUnsafe()
                // If collision was detected, but not resolved, notify event handler
                if (state == State.COLLISION_DETECTED) {
                    incompletableOperationCallback?.invoke(this)
                }
            } catch (t: Throwable) {
                this.errorState = ErrorState.ERROR
                this.throwable = t
                // If error was detected, notify event handler
                incompletableOperationCallback?.invoke(this)
            }
        }

        protected fun throwEx(msg: String, t: Throwable? = null) {
            throw PasteException(srcFile, originalTarget, actualTarget, msg).initCause(t)
        }

        /**
         * Indicates that there is an unresolved collision and the operation is not complete. This is not related to
         * [hasUnresolvedError].
         */
        fun hasUnresolvedCollision() = state == State.COLLISION_DETECTED

        /**
         * Indicates whether this operation is completed. This means that there are no unresolved errors or collisions.
         */
        fun isCompleted() = state == State.COMPLETED

        /**
         * Indicates that there is an unresolved error and the operation is not complete. This is not related to
         * [hasUnresolvedCollision].
         */
        fun hasUnresolvedError() = errorState != ErrorState.NONE
    }

    class PasteException(src: Path, target: Path, val actualTarget: Path, message: String) :
        FileSystemException(
            src.toFile(), target.toFile(),
            "(${actualTarget}) " + message
        )

    private enum class State {
        INIT,
        COLLISION_DETECTED,
        COLLISION_RESOLVED,

        /**
         * The target file has been successfully pasted, but the possible source file deletion has not been handled yet.
         */
        TARGET_PASTED,
        COMPLETED
    }

    private enum class ErrorState {
        NONE, ERROR
    }

    /** Defines how paste collisions should be handled in a [FilePasteManger] */
    enum class CollisionStrategy {
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

    /** An error strategy dictates what happens when a [PasteOperation] is executed after an error has been detected. */
    enum class ErrorStrategy {
        /** Default strategy for every operation. Do nothing and wait for the caller to set another strategy. */
        RESOLVE_LATER,

        /** Sets the operation's state to completed, effectively skipping it. */
        MARK_COMPLETED,

        /** Clear the error state and set the strategy to [RESOLVE_LATER]. Then execute the operation again. */
        RETRY
    }
}
