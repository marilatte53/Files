package org.example

import java.nio.file.Path

// Persistence is responsible for setting these values, even if we failed to read state from disk
class ExplorerState(
    val currentDir: Path,
    /**
     * Relative path
     */
    val selectedDir: Path?
)