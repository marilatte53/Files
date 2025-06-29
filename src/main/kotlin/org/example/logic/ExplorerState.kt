package org.example.logic

import java.nio.file.Path

class ExplorerState(
    var currentDir: Path,
) {
    var cachedFileList: MutableList<Path> = mutableListOf()
}