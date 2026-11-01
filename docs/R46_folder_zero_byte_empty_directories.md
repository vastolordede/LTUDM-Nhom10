# R46 - zero byte files, nested and empty folders

Migration v8 stores nested relative directory paths in `folder_directories`.
The desktop Share flow now includes 0-byte regular files and empty directory
metadata, including groups with no files. The Download flow reconstructs all
nested directories before downloading the file children, and can create an
entirely empty root folder. Folder completion evidence permits zero child
transfers only when the folder itself exists and has zero files.

Safety: existing FolderPathResolver blocks traversal, absolute paths and
pre-existing symlink hops. FolderScanner does not follow symlinks and now
reports unreadable entries rather than silently creating incomplete shares.

Not yet validated against a live PostgreSQL DB: v8 migration, nested paths on
Windows, mixed-case collisions, 0-byte P2P transfer and share/stop/re-share.
