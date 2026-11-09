package vn.edu.p2p.peer.transfer;

import vn.edu.p2p.common.dto.FolderFileInfo;
import vn.edu.p2p.peer.share.FileHashUtil;
import java.nio.file.Files;

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
        FolderDownloadPlan plan = createPlan(safeRoot, files, targets);
        for (FileTarget item : plan.files()) {
            Path target = item.target();
            if (Files.exists(target)) {
                boolean exact = Files.isRegularFile(target) && !Files.isSymbolicLink(target)
                        && Files.size(target) == item.file().fileSize()
                        && FileHashUtil.sha256(target).equalsIgnoreCase(item.file().fileHash());
                // A P2P-owned partial artifact is eligible for a safe resume.
                boolean resumable = Files.isRegularFile(target) && !Files.isSymbolicLink(target)
                        && ResumeOwnershipMarker.matchesExpectedFile(target,
                                item.file().fileHash(), item.file().fileSize());
                if (!exact && !resumable) {
                    throw new IOException("Folder destination already contains a conflicting file: "
                            + item.file().relativePath());
                }
            }
        }
        return plan;
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
