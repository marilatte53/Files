package mar.io.file_explorer.base_logic

import java.nio.file.Path

data class ExplorerFavoriteEntry(
    var name: String,
    var path: Path
)