package org.example.persistence

import java.nio.file.Path

/**
 * Used to read and write explorer state from and to files. We use a this class to also store selectedFile.
 * This class does not validate any data
 */
class ExplorerPersistentState(
    val currentDir: Path,
    /**
     * Relative path from currentDir
     */
    val selectedPath: Path?
)