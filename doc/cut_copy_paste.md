# Cut

## Idea & Motivation

So it turns out, that the Java Clipboard is not meant to be used for cut operations.
Or maybe Windows just sucks because some people argue its a bad feature anyway.
However, I still want to somewhat support it (at least in the scope of this application).

Store cut operations in the system clipboard, using the conventional as well as a
custom flavor.
On paste, this application will recognize the flavor, and after pasting them,
delete the original files.  
Drawback: cut behavior will only work in this application, other programs will simply paste

## Implementation

Due to clipboard limitations cut will only properly work when the cut AND paste
operations are done in this application:  
otherwise the files will be pasted normally, but the original files will not be deleted.

# Paste

- source files: the files that were copied by the user
- target files: the files where the copied files should be pasted when the user issues a
  paste action
- file collisions: when a file with the same name as the target file already exists

## Idea

There are different "paste collision modes" that dictate what happens when encountering
collisions. The user should be able to choose between these modes.

## Implementation

The application differentiates between **single-file operations** and 
**multi-file operations**. WIP Both of these operation types have a dedicated setting 
specifying their respective behavior using collision modes.

**Paste Collision behavior:**
For single-file ops the default is sibling mode.
multi-file ops WIP TODO

### Paste Collision Modes

- **sibling mode:**  
  On paste, when a target file already exists, the matching source file will receive a new
  name, referencing the original name of the source file.
