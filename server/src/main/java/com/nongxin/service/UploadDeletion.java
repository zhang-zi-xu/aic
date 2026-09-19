package com.nongxin.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/** One deletion's private recovery copy. Never scans or purges another operation's directory. */
final class UploadDeletion implements TransactionSynchronization {
    private static final Logger log = LoggerFactory.getLogger(UploadDeletion.class);
    private final String id;
    private final Path root;
    private final Path original;
    private Path recoveryDirectory;
    private Path backup;
    private boolean originalPresent;
    private boolean unlinkAttempted;
    private boolean commitStarted;
    private int completion = STATUS_UNKNOWN;

    UploadDeletion(Path uploadDirectory, String id, String extension) {
        if (id == null || !id.matches("img-[A-Za-z0-9_-]+")
                || extension == null || !extension.matches("[A-Za-z0-9]{1,10}")) {
            throw new UploadService.DeleteUnavailable();
        }
        this.id = id;
        root = uploadDirectory.toAbsolutePath().normalize();
        original = root.resolve(id + "." + extension).normalize();
        if (!root.equals(original.getParent())) throw new UploadService.DeleteUnavailable();
    }

    void prepare() throws IOException {
        BasicFileAttributes attributes;
        try { attributes = Files.readAttributes(original, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
        catch (NoSuchFileException missing) { return; } // Missing is distinct from inaccessible/unknown.
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()
                || !original.toRealPath().getParent().equals(root.toRealPath())) {
            throw new UploadService.DeleteUnavailable();
        }
        originalPresent = true;
        recoveryDirectory = Files.createTempDirectory(root, ".delete-" + id + "-");
        backup = recoveryDirectory.resolve("image.backup");
        Files.copy(original, backup, LinkOption.NOFOLLOW_LINKS);
        if (!Files.readAttributes(backup, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isRegularFile()) {
            throw new UploadService.DeleteUnavailable();
        }
    }

    void unlink() throws IOException {
        if (!originalPresent) return; // Do not delete a new file that appeared after a missing-file observation.
        unlinkAttempted = true;
        Files.deleteIfExists(original);
    }

    @Override public void beforeCommit(boolean readOnly) { commitStarted = true; }
    @Override public void afterCompletion(int status) { completion = status; }

    void failed() {
        if (!unlinkAttempted) {
            discard(); // Original was never touched; this includes partial backup-copy failures.
            return;
        }
        if (completion != STATUS_ROLLED_BACK || commitStarted) {
            retained("提交或回滚结果未确认");
            return;
        }
        try {
            // Never overwrite a file recreated by another actor. Only a byte-identical regular file is redundant.
            BasicFileAttributes current = null;
            try { current = Files.readAttributes(original, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
            catch (NoSuchFileException missing) { /* Restore below, with no REPLACE_EXISTING. */ }
            if (current == null) Files.copy(backup, original);
            else if (!current.isRegularFile() || current.isSymbolicLink() || Files.mismatch(original, backup) != -1) {
                retained("原路径已有不同内容，未覆盖");
                return;
            }
            discard();
        } catch (IOException | RuntimeException failure) {
            retained("恢复未完成"); // Preserve the copy even if restoration partially wrote the destination.
        }
    }

    /** Called only after confirmed commit or after the original is known safe. */
    boolean discard() {
        try {
            if (backup != null) Files.deleteIfExists(backup);
        } catch (IOException | RuntimeException failure) {
            retained("副本清理未完成");
            return false;
        }
        try {
            if (recoveryDirectory != null) Files.deleteIfExists(recoveryDirectory); // Empty only; never recursive.
        } catch (IOException | RuntimeException failure) {
            retained("空恢复目录清理未完成");
        }
        return true;
    }

    private void retained(String reason) {
        log.warn("[upload] 删除 {}：{}，恢复目录标识 {}，需人工核对", id, reason,
                recoveryDirectory == null ? "未创建" : recoveryDirectory.getFileName());
    }
}
