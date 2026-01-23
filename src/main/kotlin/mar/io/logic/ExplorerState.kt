package mar.io.logic

import mar.io.persistence.StorageManager
import java.nio.file.Path
import java.time.Instant

class ExplorerState(
    val controller: ExplorerController,
    var currentDir: Path,
) {
    /**
     * Cached file list. This should always be used in the GUI instead of
     * reading the files from disk directly
     */
    var cachedFileList: List<Path> = emptyList()

    val favorites = CachedUpdatingResource(controller.storage.favorites, mutableListOf())

    val directoriesAccessed = CachedUpdatingResource(controller.storage.directoriesAccessed, DirectoriesAccessed())

    /**
     * stores dirs that were accessed by the user at any point during this
     * session
     */
    inner class DirectoriesAccessed {
        // This won't scale well but a tactical restart will fix it
        protected var list = mutableListOf<DirectoryAccessEntry>()
        protected var reversed = list.asReversed()

        /** call this when a user accesses a directory */
        fun notify(path: Path): Boolean {
            // path should always be in a usable state
//            val pathUsable = path.absolute().normalize()
            val ind = list.indexOfFirst { it.path == path }
            if (ind < 0) return list.add(DirectoryAccessEntry(path, 1))
            // move existing entry to the end of the list and add 1 to ac
            val entry = list[ind]
            list.removeAt(ind)
            entry.accessCount++
            list.add(entry)
            return false
        }

        /** should only be called when the list is still empty */
        fun setInitialEntries(list: List<Path>) { //Ratte DnBummZua
            this.list.addAll(list.map { DirectoryAccessEntry(it, 1) })
        }

        fun clearEntries() {
            this.list.clear()
        }

        // Reversed list is ordered by access time. By using it we maintain that order when paths have the same AC
        fun sortedByAccessCount(max: Long): List<Path> =
            reversed.stream().sorted(Comparator.reverseOrder()).limit(max).map { it.path }.toList()

        /** reverse to read the element last added as first */
        fun sortedByAccessTime(max: Long): List<Path> = reversed.stream().limit(max).map { it.path }.toList()
    }

    inner class CachedUpdatingResource<R>(
        val resourceFile: StorageManager.ResourceFile<R>,
        val initRsrc: R
    ) {
        val name = resourceFile.name
        protected var cachedResource = initRsrc
        protected var lastCacheUpdateTime: Instant? = null
        protected var lastFailedWriteTime: Instant? = null

        fun readAndWrite(updateFunction: (rsrc: R) -> Unit) {
            readAndGet().also { updateFunction(it) }.let { setAndWrite(it) }
        }

        fun readAndGet(): R {
            debug("Does the cache need an update?")
            val fileLastModifiedTime = resourceFile.getLastModifiedTime()
            if (fileLastModifiedTime == null) {
                debug("Could not retrieve file modification time, returning outdated cache")
                return cachedResource
            }
            val updateTime = lastCacheUpdateTime
            if (updateTime != null && !updateTime.isBefore(fileLastModifiedTime)) {
                debug("Cache is up-to-date, no need to read file")
                // Try to fix previously failed write attempts
                if (lastFailedWriteTime != null) {
                    debug("Detected previously failed write attempt. Re-attempting now, since the file is still outdated")
                    tryWriteCacheToFile()
                }
                return cachedResource
            }
            debug("Cache is outdated, reloading from file")
            val newResourceFromFile = resourceFile.readResourceFromFile(cachedResource)
            if (newResourceFromFile == null) {
                debug("Resource could not be read from file, returning outdated cache")
                return cachedResource
            }
            this.cachedResource = newResourceFromFile
            this.lastCacheUpdateTime = fileLastModifiedTime
            debug("Success! Returning resource from updated cache")
            return cachedResource
        }

        fun setAndWrite(newResource: R) {
            this.cachedResource = newResource
            tryWriteCacheToFile()
        }

        protected fun tryWriteCacheToFile() {
            try {
                resourceFile.writeResourceToFile(cachedResource)
                // for safety reasons use the actual timestamp instead of Instant.now
                val fileNewModifiedTime = resourceFile.getLastModifiedTime()
                this.lastCacheUpdateTime = fileNewModifiedTime
                // Indicates, that the write attempt was successful
                this.lastFailedWriteTime = null
            } catch (e: Exception) {
                debug("Failed to write new resource to file: ${e.message}")
                this.lastCacheUpdateTime = Instant.now()
                this.lastFailedWriteTime = lastCacheUpdateTime
            }
        }

        protected fun debug(message: Any?) {
            println("DEBUG [cached resource '$name'] $message")
        }
    }
}