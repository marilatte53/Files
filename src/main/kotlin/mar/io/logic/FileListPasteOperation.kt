package mar.io.logic

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
 * [FileHandle.collisionMode]
 */
class FileListPasteOperation(
    val controller: ExplorerController,
    sourceFiles: List<Path>,
    val destinationDir: Path,
    val deleteSourceFiles: Boolean,
    var defaultCollisionMode: PasteCollisionMode
) {
    private val srcFiles: Map<Path, FileHandle>

    /**
     * Whether the operation is done. Regardless of the return value, this does not mean that the entire operation has
     * finished successfully.
     */
    var isDone: Boolean = false
        private set

    /** the index of the source file that has not yet been handled. When the entire operation has finished, */
    private var srcIndex: Int = 0

    /** Creates and immediately attempts to execute a [mar.io.logic.FileListPasteOperation]. */
    init {
        if (sourceFiles.isEmpty())
            throw IllegalStateException("Source file list is empty.")
        if (!destinationDir.exists())
            throw IllegalStateException("Destination directory does not exist")
        if (!destinationDir.isDirectory())
            throw IllegalStateException("Destination is not a directory")
        // TODO: check if destination is in possible source dir
        // associate creates a copy to make the resulting map immutable
        this.srcFiles = sourceFiles.associate { file -> (file to FileHandle(file)) }
        // execute paste operation and set status
        this.isDone = srcFiles.all { (src, handle) ->
            handle.tryExecute()
            return@all handle.isDone()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    inner class FileHandle(val srcFile: Path) {
        val originalTarget: Path

        /** Used when collisionMode is [PasteCollisionMode.CREATE_SIBLING]. */
        var actualTarget: Path
        var state: FilePasteState = FilePasteState.INIT
        var throwable: Throwable? = null
        var collision: PasteCollision? = null

        /** Can be used to override [mar.io.logic.FileListPasteOperation.defaultCollisionMode]. */
        var collisionMode: PasteCollisionMode? = null

        init {
            this.originalTarget = destinationDir.resolve(srcFile.name)
            this.actualTarget = originalTarget
        }

        fun effectiveCollisionMode() = collisionMode ?: defaultCollisionMode

        private fun checkForCollision() {
            if (actualTarget.exists()) {
                this.state = FilePasteState.COLLISION_DETECTED
                if (this.collision == null) {
                    this.collision = PasteCollision(effectiveCollisionMode())
                }
                return
            }
            this.state = FilePasteState.COLLISION_RESOLVED
        }

        private fun executeUnsafe() {
            if (!srcFile.exists()) {
                throw NoSuchFileException(srcFile.toRealPath().toFile(), reason = "Source file does not exist")
            }
            if (state == FilePasteState.INIT) {
                checkForCollision()
            }
            if (state == FilePasteState.COLLISION_DETECTED) {
                // first update the collision mode
                this.collision!!.collisionMode = effectiveCollisionMode()
                // try to resolve the collision
                try {
                    if (this.collision!!.resolve()) {
                        this.state = FilePasteState.COLLISION_RESOLVED
                    } else return
                } catch (t: Throwable) {
                    throw PasteCollisionException(
                        srcFile, originalTarget, actualTarget,
                        "Error while resolving paste collision."
                    ).initCause(t)
                }
            }
            if (state == FilePasteState.COLLISION_RESOLVED) {
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
                this.state = FilePasteState.TARGET_PASTED
            }
            if (state == FilePasteState.TARGET_PASTED) {
                if (!deleteSourceFiles) {
                    this.state = FilePasteState.DONE
                    return
                }
                // TODO: delete source (verify that the target was pasted)
            }
        }

        /** If the operation for this file is already done, does nothing. */
        fun tryExecute() {
            if (this.isDone())
                return
            this.throwable = null
            try {
                executeUnsafe()
            } catch (t: Throwable) {
                this.throwable = t
            }
        }

        fun isDone() = !didFail() && state == FilePasteState.DONE

        fun didFail() = throwable != null

        inner class PasteCollision(
            /**
             * The collision mode that was last used in the resolution attempt of this collision.
             * [mar.io.logic.FileListPasteOperation.FileHandle.collisionMode] specifies the collision mode to use for
             * the next attempt.
             */
            var collisionMode: PasteCollisionMode,
        ) {
            /**
             * For this function to do something, [FileHandle.state] must be equal to
             * [FilePasteState.COLLISION_DETECTED].
             *
             * @throws PasteCollisionException
             */
            fun resolve(): Boolean {
                if (state != FilePasteState.COLLISION_DETECTED)
                    return true
                when (collisionMode) {
                    PasteCollisionMode.CREATE_SIBLING -> {
                        var copies = 0
                        while (actualTarget.exists()) { // TODO: safe-guard: limit amount of copies or smth
                            actualTarget = originalTarget.resolveSibling(
                                "${originalTarget.nameWithoutExtension}" +
                                        "_copy${copies++}.${originalTarget.extension}"
                            )
                        }
                        return true
                    }


                    PasteCollisionMode.MARK_RESOLVED -> {
                        this@FileHandle.state = FilePasteState.DONE
                        return true
                    }
                    // TODO: overwrite mode; directory merge mode
                    PasteCollisionMode.RESOLVE_LATER -> {
                        return false
                    }
                }
            }
        }
    }

    class PasteCollisionException(src: Path, target: Path, val actualTarget: Path, message: String) :
        FileSystemException(
            src.toRealPath().toFile(), target.toRealPath().toFile(),
            "(${actualTarget.toRealPath().invariantSeparatorsPathString}) " + message
        )

    /** Defines how paste collisions should be handled in a [mar.io.logic.FileListPasteOperation] */
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

    enum class FilePasteState {
        INIT,
        COLLISION_DETECTED,
        COLLISION_RESOLVED,

        /**
         * The target file has been successfully pasted, but the possible source file deletion has not been handled yet.
         */
        TARGET_PASTED,
        DONE
    }
}
