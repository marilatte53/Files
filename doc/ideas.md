# Custom Explorer App

## Not done

- persistence
    - histories
    - other settings in the future
- multiple views
    - 2 default
    - tab to switch views
    - ? add '..' at the top of folder view; when entering dir, select first entry?
- navigation:
    - telescope dirs: hotkey? do we count files? -> two hotkeys?
    - favorites
        - hotkey to a certain favorite (CTRL+1 or smth)
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
    - a 'search history'??
- advanced search:
    - CTRL+F
    - regex ALT+X
    - match case ALT+C
    - presets: e.g. images with file endings
- view modes:
    - folded: default
    - tree: left/right to (un-)fold directories and view files in them, hotkeys to affect entire dir
    - flat: view all files in all subdirectories
      https://www.comp.nus.edu.sg/~cs3283/ftp/Java/swingConnect/tech_topics/tables-trees/tables-trees.html
- context menu:
    - manual options
    - open terminal
    - open explorer?
    - fix the location when using the context menu key?
- batch renaming tool

## Done

- navigation:
    - up & down
    - address bar, focus with CTRL+L
    - backspace to leave dir
    - favorites
        - CTRL+D
        - persistence
        - accelerators
    - enter:
        - enter dir
        - execute/open file
    - recents (CTRL+R)
        - by access time
        - by access count
        - different accelerators for both types
- filter dir
    - exit tf with ESC, clear with ESC when not in tf
    - order startsWith-filter first, then contains-filter
    - CTRL+S to enter textfield
    - start typing anything
- modification:
    - create directory with CTRL+N
    - create file with CTRL+SHIFT+N
    - move file to bin with DEL, no confirmation
- persistence
    - local storage
    - previously opened dir
    - (selected dir)
    - favorites