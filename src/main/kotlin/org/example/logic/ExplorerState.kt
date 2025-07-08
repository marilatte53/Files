package org.example.logic

import java.nio.file.Path

class ExplorerState(
    var currentDir: Path,
) {
    /** Cached file list. This should always be used in the GUI instead of reading the files from disk directly */
    var cachedFileList: List<Path> = emptyList()

    var favoriteList = mutableListOf<ExplorerFavoriteEntry>()
}