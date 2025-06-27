package org.example

import java.nio.file.Path

// Persistence is responsible for setting these values, even if we failed to read state from disk
class ExplorerState(
    var currentDir: Path,
    /**
     * Relative path
     */
    var selectedFile: Path?
) {
    /**
     * Check the state for illegal values and correct them. Call this after reading in the state from a file for example
     */
    fun sanitize() {
        val selFile = selectedFile // Make it thread-safe and shut up the compiler's null warning when calling .parent
        if (selFile != null) {
            // Check whether the file is even in the current dir
            if (selFile.parent != currentDir) {
                // TODO: should the sel file even be in the state? If so, when and how do we update it, since it changes
                // so frequently in the UI. This means we need another obj for Persistence read that does contain the selFile
                
                // load the file list into the state somewhere and set the sel file from that 
                selectedFile = null 
                println("INFO: Corretcted illegal value in state: selected file is not in current dir.")
            }
        }
    }
}