# Files Explorer

by Mario for Mario

## Ideas

- store recents
- status bar?
- logs
- highlight filter textfield for visibility
- multiple views
- navigation:
    - telescope dirs: hotkey? do we count files? -> two hotkeys?
    - switch drives (CTRL+SHIFT+D?)
- modification:
    - CTRL+C, CTRL+V, CTRL+X
    - CTRL+Z (CTRL+Y)
    - F2 to rename file, move up & down while renaming
- selection:
    - when pressing space, introduce a second selection layer -> only these count ???
    - cache selections when leaving dirs??
- history:
    - save navigation history
    - use ALT + left/right to navigate history
- advanced search: CTRL+F; regex ALT+X; match case ALT+C; presets: e.g. images with file endings
- view modes:
    - folded: default
    - tree: left/right to (un-)fold directories and view files in them, hotkeys to affect entire dir
    - flat: view all files in all subdirectories
      https://www.comp.nus.edu.sg/~cs3283/ftp/Java/swingConnect/tech_topics/tables-trees/tables-trees.html
- context menu:
    - fix the location when using the context menu key
    - open explorer
    - open terminal
- batch renaming tool

## Usage

- address bar focus with CTRL+L
- favorites CTRL+D
- recents (CTRL+R)
    - by access time
    - by access count
- filter dir CTRL+S
    - exit filter text field with ESC, clear filter text with one more ESC press
    - startsWith-filter first, then contains-filter
    - typing any letter starts the filter
- modification:
    - create directory with CTRL+N
    - create file with CTRL+SHIFT+N
    - move file to bin with DEL, no confirmation
- persistence
    - stores in install dir
    - previously opened dir
    - previously selected dir
    - favorites