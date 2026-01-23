# Files Explorer

## Recent Directories

**Idea 1**
- Store directories recently accessed, sorting them by **access count**
- When too many in the list, remove the **least significant entry**
- The **LSE** should depend on the time it was last accessed as well as the access count
- This should probably be done **per session**

**Idea 2**
- Store directories recently accessed in a single session
- On session end, store entries with the highest **access count** to disk
- On load, prioritize new entries while keeping the old ones when accessed again

**Idea 3**
- Store n directories with the highest access count in a session
- Store additional m directories that were accessed recently
- On session start: load previous entries only as recents

**Implementation**
All sessions use the same dynamically updating file -> they share recents.
For now only implement "by access time"
