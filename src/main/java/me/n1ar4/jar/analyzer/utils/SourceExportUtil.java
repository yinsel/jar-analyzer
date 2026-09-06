/*
 * GPLv3 License
 *
 * Copyright (c) 2022-2026 4ra1n (Jar Analyzer Team)
 *
 * This project is distributed under the GPLv3 license.
 *
 * https://github.com/jar-analyzer/jar-analyzer/blob/master/LICENSE
 */

package me.n1ar4.jar.analyzer.utils;

import me.n1ar4.jar.analyzer.entity.ClassFileEntity;
import me.n1ar4.jar.analyzer.starter.Const;
import me.n1ar4.log.LogManager;
import me.n1ar4.log.Logger;
import org.objectweb.asm.ClassReader;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Exports decompiled matched classes beside the bytecode export directory.
 */
public final class SourceExportUtil {
    private static final Logger logger = LogManager.getLogger();

    private SourceExportUtil() {
    }

    /**
     * Writes source code that has already been produced by the active index
     * build. This method deliberately does not invoke a decompiler: the same
     * decompilation result is consumed by both source export and Lucene.
     */
    public static boolean export(ClassFileEntity classFile, String source,
                                 Path tempDir) {
        if (classFile == null || classFile.getPath() == null
                || source == null || source.trim().isEmpty()) {
            return false;
        }

        try {
            String internalName = resolveInternalName(classFile);
            Path target = sourceExportPath(tempDir, internalName);
            writeAtomically(target, source);
            logger.debug("export matched source: {}", target);
            return true;
        } catch (Exception e) {
            logger.warn("export matched source failed: {} ({})",
                    classFile.getClassName(), e.toString());
            return false;
        }
    }

    private static void writeAtomically(Path target, String source)
            throws IOException {
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("source target has no parent");
        }

        Path temporary = Files.createTempFile(
                parent, "." + target.getFileName(), ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(
                    temporary,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS)) {
                output.write(source.getBytes(StandardCharsets.UTF_8));
            }
            try {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target,
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static Path sourceExportPath(Path tempDir,
                                 String internalClassName) throws IOException {
        if (tempDir == null) {
            throw new IOException("temp directory is null");
        }
        if (internalClassName == null || internalClassName.trim().isEmpty()) {
            throw new IOException("class name is empty");
        }

        Path absoluteTempDir = tempDir.toAbsolutePath().normalize();
        Path parent = absoluteTempDir.getParent();
        if (parent == null) {
            throw new IOException("temp directory has no parent");
        }
        Path sourcesDir = parent.resolve(Const.sourcesDir);
        return JarUtil.safeExtractionPath(
                sourcesDir, internalClassName + ".java");
    }

    private static String resolveInternalName(ClassFileEntity classFile)
            throws IOException {
        byte[] classBytes = classFile.getFile();
        if (classBytes != null) {
            try {
                String internalName = new ClassReader(classBytes).getClassName();
                if (internalName != null && !internalName.trim().isEmpty()) {
                    return internalName;
                }
            } catch (RuntimeException e) {
                logger.warn("read class name for source export failed: {} ({})",
                        classFile.getClassName(), e.toString());
            }
        }

        String archiveName = ClassNameFilter.normalizeArchiveClassPath(
                classFile.getClassName());
        if (!archiveName.endsWith(".class")) {
            throw new IOException("invalid class name: "
                    + classFile.getClassName());
        }
        return archiveName.substring(0,
                archiveName.length() - ".class".length());
    }
}
