# Cut, Copy & Paste

## Terminology

- source files: the files that were copied by the user
- target files: the files where the copied files should be pasted when the user issues a
  paste action
- file paste collision: when a file with the same name as the target file already exists
  in the context of a paste operation

## Limitations

So it turns out, that the Java Clipboard is not meant to be used for cut operations.
Or maybe Windows just sucks because some people argue its a bad feature anyway.
However, I still want to somewhat support it (at least in the scope of this application).

Store cut operations in the system clipboard, using the conventional as well as a
custom flavor.
On paste, this application will recognize the flavor, and after pasting them,
delete the original files.  
Drawback: cut behavior will only work in this application, other programs will simply paste.

## Implementation

I hope the code documentation is enough.
