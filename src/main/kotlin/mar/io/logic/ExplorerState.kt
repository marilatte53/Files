package mar.io.logic

import java.nio.file.Path
import java.time.Instant
import java.util.Comparator

class ExplorerState(
    var currentDir: Path,
) {
    /** Cached file list. This should always be used in the GUI instead of reading the files from disk directly */
    var cachedFileList: List<Path> = emptyList()

    /** when were the favorites loaded from disk? */
    var favoriteCacheTime: Instant? = null
    var favoriteCache = mutableListOf<ExplorerFavoriteEntry>()

    val directoriesAccessed = DirectoriesAccessed()

    /** stores dirs that were accessed by the user at any point during this session */
    inner class DirectoriesAccessed {
        // This won't scale well but a tactical restart will fix it
        protected var list = mutableListOf<DirectoryAccessEntry>()
        protected var reversed = list.asReversed()

        /** call this when a user accesses a directory */
        fun notify(path: Path): Boolean {
            // path should always be in a usable state
//            val pathUsable = path.absolute().normalize()
            val ind = list.indexOfFirst { it.path.equals(path) }
            if (ind < 0) return list.add(DirectoryAccessEntry(path, 1))
            // move existing entry to the end of the list and add 1 to ac
            val entry = list[ind]
            list.removeAt(ind)
            entry.accessCount++
            list.add(entry)
            return false
        }

        /** should only be called when the list is still empty */
        fun setInitialEntries(list: List<Path>) {
            this.list.addAll(list.map { DirectoryAccessEntry(it, 1) })
        }

        // Reversed list is ordered by access time. This maintains AT order when paths have the same AC
        fun sortedByAccessCount(max: Long): List<Path> =
            reversed.stream().sorted(Comparator.reverseOrder()).limit(max).map { it.path }.toList()

        /** reverse to read the element last added as first */
        fun sortedByAccessTime(max: Long): List<Path> = reversed.stream().limit(max).map { it.path }.toList()
    }
}