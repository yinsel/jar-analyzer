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

import me.n1ar4.jar.analyzer.engine.DecompileEngine;
import me.n1ar4.jar.analyzer.entity.ClassFileEntity;
import me.n1ar4.jar.analyzer.starter.Const;
import me.n1ar4.log.LogManager;
import me.n1ar4.log.Logger;
import org.objectweb.asm.ClassReader;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Exports decompiled matched classes beside the bytecode export directory.
 */
public final class SourceExportUtil {
    private static final Logger logger = LogManager.getLogger();

    private SourceExportUtil() {
    }

    public static int export(List<ClassFileEntity> classFiles, Path tempDir) {
        // Paths under the extraction directory are reused between builds.
        // Clear path-keyed decompiler results so exported sources always match
        // the class bytes collected for the current analysis.
        DecompileEngine.cleanCache();
        return export(classFiles, tempDir, DecompileEngine::decompile);
    }

    static int export(List<ClassFileEntity> classFiles, Path tempDir,
                      SourceDecompiler decompiler) {
        if (classFiles == null || classFiles.isEmpty()) {
            return 0;
        }

        int exported = 0;
        for (ClassFileEntity classFile : classFiles) {
            if (classFile == null || classFile.getPath() == null) {
                continue;
            }
            try {
                String source = decompiler.decompile(classFile.getPath());
                if (source == null || source.trim().isEmpty()) {
                    logger.warn("export matched source returned no code: {}",
                            classFile.getClassName());
                    continue;
                }

                String internalName = resolveInternalName(classFile);
                Path target = sourceExportPath(tempDir, internalName);
                try (OutputStream output = java.nio.file.Files.newOutputStream(
                        target,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS)) {
                    output.write(source.getBytes(StandardCharsets.UTF_8));
                }
                exported++;
                logger.debug("export matched source: {}", target);
            } catch (Exception e) {
                logger.warn("export matched source failed: {} ({})",
                        classFile.getClassName(), e.toString());
            }
        }
        return exported;
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

    @FunctionalInterface
    interface SourceDecompiler {
        String decompile(Path classFilePath);
    }
}
