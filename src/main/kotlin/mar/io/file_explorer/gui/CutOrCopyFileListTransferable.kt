package mar.io.file_explorer.gui

import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File
import java.nio.file.Path

class CutOrCopyFileListTransferable(
    val copiedPaths: List<Path>,
    val isCutOperation: Boolean = false
) : Transferable {
    // TODO: use this in the controller instead of the GUI
    companion object {
        val CUT_FILE_LIST_FLAVOR =
            DataFlavor("application/x-java-cut-path-list;class=java.util.List", "List of Paths (cut)")

        fun getOrNull(clipboard: Clipboard): CutOrCopyFileListTransferable? {
            val flavors = clipboard.availableDataFlavors
            try {
                if (flavors.contains(CUT_FILE_LIST_FLAVOR)) {
                    @Suppress("UNCHECKED_CAST")
                    return CutOrCopyFileListTransferable(clipboard.getData(CUT_FILE_LIST_FLAVOR) as List<Path>, true)
                } else if (flavors.contains(DataFlavor.javaFileListFlavor)) {
                    @Suppress("UNCHECKED_CAST")
                    return CutOrCopyFileListTransferable(
                        (clipboard.getData(DataFlavor.javaFileListFlavor) as List<File>).map { it.toPath() },
                        false
                    )
                }
            } catch (_: ClassCastException) {
            }
            return null
        }
    }

    val flavors: Array<DataFlavor> =
        if (isCutOperation)
            arrayOf(DataFlavor.javaFileListFlavor, CUT_FILE_LIST_FLAVOR)
        else
            arrayOf(DataFlavor.javaFileListFlavor)

    override fun getTransferDataFlavors(): Array<out DataFlavor> {
        return flavors
    }

    override fun isDataFlavorSupported(flavor: DataFlavor?): Boolean {
        return flavors.contains(flavor)
    }

    override fun getTransferData(flavor: DataFlavor?): List<Any> {
        if (flavor == DataFlavor.javaFileListFlavor) return copiedPaths.map(Path::toFile)
        if (flavor == CUT_FILE_LIST_FLAVOR) return copiedPaths
        throw UnsupportedFlavorException(flavor)
    }
}