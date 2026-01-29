# Files Explorer

by Mario for Mario

## Ideas

- modification:
    - F2 to rename file, move up & down while renaming
- highlight filter textfield for visibility
- selection
- navigation history
- modification history (store checksums or smth so we don't undo the wrong stuff;
  also make backups or smth)
- context menu:
    - open explorer
    - open terminal
    - fix the location when using the context menu key
- status bar?
- multiple views
- logs
- navigation:
    - telescope dirs: hotkey? do we count files? -> two hotkeys?
    - switch drives (CTRL+SHIFT+D?)
- advanced search: CTRL+F; regex ALT+X; match case ALT+C; presets: e.g. images with file endings
- view modes:
    - folded: default
    - tree: left/right to (un-)fold directories and view files in them, hotkeys to affect entire dir
    - flat: view all files in all subdirectories
      https://www.comp.nus.edu.sg/~cs3283/ftp/Java/swingConnect/tech_topics/tables-trees/tables-trees.html
- batch renaming tool

## Features & Usage

- address bar focus with CTRL+L
- favorites CTRL+D
- recents (CTRL+R)
    - by access time
    - by access count
    - recents are persistent but only retain access time information
- filter dir CTRL+S
    - exit filter text field with ESC, clear filter text with one more ESC press
    - startsWith-filter first, then contains-filter
    - typing any letter starts the filter
- modification:
    - create directory with CTRL+N
    - create file with CTRL+SHIFT+N
    - move file to bin with DEL, no confirmation
    - copy CTRL+C; paste CTRL+V; cut CTRL+X
        - cut will only work properly when pasted inside this application
        - see `doc/cut_copy_paste.md` for more information
- persistence
    - stores in install dir
    - previously opened dir
    - previously selected dir
    - favorites