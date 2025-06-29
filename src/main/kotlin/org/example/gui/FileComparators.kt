package org.example.gui

import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

class FileComparators {
    companion object {
        val FILE_DIR = object : Comparator<Path> {
            protected fun priority(p: Path): Int {
                if (p.isDirectory())
                    return 1
                if (p.isRegularFile())
                    return 2
                return 0
            }

            override fun compare(o1: Path?, o2: Path?): Int {
                requireNotNull(o1)
                requireNotNull(o2)
                return priority(o1) - priority(o2)
            }
        }

        val UNDERSCORE_FIRST = object : Comparator<Path> {
            override fun compare(o1: Path?, o2: Path?): Int {
                requireNotNull(o1)
                requireNotNull(o2)
                if (o1.startsWith("_"))
                    return if (o2.startsWith("_")) 0 else -1
                return 1
            }
        }
    }
}