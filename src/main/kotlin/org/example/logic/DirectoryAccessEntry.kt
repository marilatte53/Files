package org.example.logic

import java.nio.file.Path

/** Used to store events where a user enters a directory in the explorer */
data class DirectoryAccessEntry(
    val path: Path,
    var accessCount: Int
    // access time not needed since entries will be sorted by insertion order (= access time) in lists
) : Comparable<DirectoryAccessEntry> {
    override fun compareTo(other: DirectoryAccessEntry): Int {
        return compareValues(this.accessCount, other.accessCount)
    }
}