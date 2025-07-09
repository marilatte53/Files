package org.example.logic

import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant

class ExplorerState(
    var currentDir: Path,
) {
    /** Cached file list. This should always be used in the GUI instead of reading the files from disk directly */
    var cachedFileList: List<Path> = emptyList()

    /** when where the favorites loaded from disk? */
    var favoriteCacheTime: Instant? = null
    var favoriteCache = mutableListOf<ExplorerFavoriteEntry>()
}