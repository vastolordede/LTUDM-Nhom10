package vn.edu.p2p.peer.transfer;

import vn.edu.p2p.common.dto.FolderFileInfo;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Validated target paths for downloading either selected files or a complete folder. */
public final class FolderDownloadPlan {
    public record FileTarget(FolderFileInfo file, Path target) {}

    private final Path root;
    private final List<FileTarget> files;
    private final List<Path> directories;

    private FolderDownloadPlan(Path root, List<FileTarget> files, List<Path> directories) {
        this.root = root;
        this.files = List.copyOf(files);
        this.directories = List.copyOf(directories);
    }

    public static FolderDownloadPlan selected(Path root, List<FolderFileInfo> selectedFiles)
            throws IOException {
        return createPlan(root, selectedFiles, List.of());
    }

    public static FolderDownloadPlan full(Path root, List<FolderFileInfo> files,
                                          List<String> directories) throws IOException {
        Path safeRoot = root.toAbsolutePath().normalize();
        List<Path> targets = new ArrayList<>(directories.size());
        for (String directory : directories) {
            targets.add(resolve(safeRoot, directory));
        }
        return createPlan(safeRoot, files, targets);
    }

    private static FolderDownloadPlan createPlan(Path root, List<FolderFileInfo> files,
                                                 List<Path> directories) throws IOException {
        Path safeRoot = root.toAbsolutePath().normalize();
        List<FileTarget> targets = new ArrayList<>(files.size());
        for (FolderFileInfo file : files) {
            targets.add(new FileTarget(file, resolve(safeRoot, file.relativePath())));
        }
        return new FolderDownloadPlan(safeRoot, targets, directories);
    }

    private static Path resolve(Path root, String relativePath) throws IOException {
        return FolderPathResolver.resolve(root, relativePath);
    }

    public Path root() {
        return root;
    }

    public List<FileTarget> files() {
        return files;
    }

    public List<Path> directories() {
        return directories;
    }
}
