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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceExportUtilTest {
    @TempDir
    Path tempDir;

    @Test
    void exportsUtf8SourceBesideClassesUsingBytecodePackageName()
            throws Exception {
        Path analyzerTemp = Files.createDirectory(
                tempDir.resolve("jar-analyzer-temp"));
        Path classFile = JarUtil.safeExtractionPath(analyzerTemp,
                "BOOT-INF/classes/archive/path/Entry.class");
        Files.write(classFile, classBytes("com/example/AuditService"));
        ClassFileEntity entity = new ClassFileEntity(
                "BOOT-INF/classes/archive/path/Entry.class", classFile, 1);
        String source = "package com.example;\n"
                + "public class AuditService { String value = \"安全\"; }\n";

        boolean exported = SourceExportUtil.export(
                entity, source, analyzerTemp);

        Path expected = tempDir.resolve(
                "sources/com/example/AuditService.java");
        assertTrue(exported);
        assertEquals(source, new String(
                Files.readAllBytes(expected), StandardCharsets.UTF_8));
    }

    @Test
    void preservesBinaryNamesForMatchedInnerClasses() throws Exception {
        Path analyzerTemp = Files.createDirectory(
                tempDir.resolve("jar-analyzer-temp"));
        Path classFile = JarUtil.safeExtractionPath(
                analyzerTemp, "demo/Outer$Nested.class");
        Files.write(classFile, classBytes("demo/Outer$Nested"));
        ClassFileEntity entity = new ClassFileEntity(
                "demo/Outer$Nested.class", classFile, 1);

        boolean exported = SourceExportUtil.export(
                entity, "package demo; class Outer$Nested {}\n",
                analyzerTemp);

        assertTrue(exported);
        assertEquals("package demo; class Outer$Nested {}\n",
                new String(Files.readAllBytes(tempDir.resolve(
                        "sources/demo/Outer$Nested.java")),
                        StandardCharsets.UTF_8));
    }

    @Test
    void skipsEmptyDecompilerResults() throws Exception {
        Path analyzerTemp = Files.createDirectory(
                tempDir.resolve("jar-analyzer-temp"));
        Path classFile = JarUtil.safeExtractionPath(
                analyzerTemp, "demo/Empty.class");
        Files.write(classFile, classBytes("demo/Empty"));
        ClassFileEntity entity = new ClassFileEntity(
                "demo/Empty.class", classFile, 1);

        boolean exported = SourceExportUtil.export(
                entity, null, analyzerTemp);

        assertFalse(exported);
        assertFalse(Files.exists(tempDir.resolve("sources/demo/Empty.java")));
    }

    @Test
    void replacesSourceAtTheSameBinaryClassPath() throws Exception {
        Path analyzerTemp = Files.createDirectory(
                tempDir.resolve("jar-analyzer-temp"));
        Path classFile = JarUtil.safeExtractionPath(
                analyzerTemp, "demo/Current.class");
        Files.write(classFile, classBytes("demo/Current"));
        ClassFileEntity entity = new ClassFileEntity(
                "demo/Current.class", classFile, 1);
        Path target = tempDir.resolve("sources/demo/Current.java");

        assertTrue(SourceExportUtil.export(entity,
                "package demo; class Current { int oldValue; }\n",
                analyzerTemp));
        assertTrue(SourceExportUtil.export(entity,
                "package demo; class Current { int newValue; }\n",
                analyzerTemp));

        assertEquals("package demo; class Current { int newValue; }\n",
                new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
    }

    @Test
    void rejectsUnsafeFallbackPaths() throws Exception {
        Path analyzerTemp = Files.createDirectory(
                tempDir.resolve("jar-analyzer-temp"));
        Path classFile = Files.createFile(analyzerTemp.resolve("invalid.class"));
        Files.write(classFile, new byte[]{1, 2, 3});
        ClassFileEntity entity = new ClassFileEntity(
                "BOOT-INF/classes/../../escape.class", classFile, 1);

        boolean exported = SourceExportUtil.export(
                entity, "class Escape {}\n", analyzerTemp);

        assertFalse(exported);
        assertFalse(Files.exists(tempDir.resolve("escape.java")));
    }

    private static byte[] classBytes(String internalName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC,
                internalName, null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }
}
