package mar.io.gui

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.nio.file.Path

class CutOrCopyFileListTransferable(
    val pathList: List<Path>,
    val cut: Boolean = false
) : Transferable {
    companion object {
        val CUT_FILE_LIST_FLAVOR =
            DataFlavor("application/x-java-cut-path-list;class=java.util.List", "List of Paths (cut)")
    }

    val flavors: Array<DataFlavor> =
        if (cut)
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
        if (flavor == DataFlavor.javaFileListFlavor) return pathList.map(Path::toFile)
        if (flavor == CUT_FILE_LIST_FLAVOR) return pathList
        throw UnsupportedFlavorException(flavor)
    }
}