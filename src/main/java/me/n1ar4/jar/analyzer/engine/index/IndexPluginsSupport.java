/*
 * GPLv3 License
 *
 * Copyright (c) 2022-2026 4ra1n (Jar Analyzer Team)
 *
 * This project is distributed under the GPLv3 license.
 *
 * https://github.com/jar-analyzer/jar-analyzer/blob/master/LICENSE
 */

package me.n1ar4.jar.analyzer.engine.index;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.thread.ExecutorBuilder;
import cn.hutool.core.util.StrUtil;
import me.n1ar4.jar.analyzer.engine.DecompileEngine;
import me.n1ar4.jar.analyzer.engine.index.entity.Result;
import me.n1ar4.jar.analyzer.entity.ClassFileEntity;
import me.n1ar4.jar.analyzer.gui.util.LogUtil;
import me.n1ar4.jar.analyzer.lucene.LuceneBuildListener;
import me.n1ar4.jar.analyzer.starter.Const;
import me.n1ar4.jar.analyzer.utils.SourceExportUtil;
import me.n1ar4.log.LogManager;
import me.n1ar4.log.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class IndexPluginsSupport {
    private static final Logger logger = LogManager.getLogger();

    public final static String CurrentPath = System.getProperty("user.dir");
    public final static String DocumentPath = CurrentPath + FileUtil.FILE_SEPARATOR + Const.indexDir;
    public final static String TempPath = CurrentPath + FileUtil.FILE_SEPARATOR + Const.tempDir;

    private static final Integer MAX_CORE = Runtime.getRuntime().availableProcessors();
    private static final Integer MAX_SIZE_GROUP = 40;
    private static final ExecutorService executorService = ExecutorBuilder.create()
            .setCorePoolSize(MAX_CORE * 2)
            .setMaxPoolSize(MAX_CORE * 3)
            .setWorkQueue(new LinkedBlockingQueue<>())
            .build();
    private static final Object ACTIVE_BUILD_LOCK = new Object();

    private static volatile boolean useActive = false;
    private static volatile boolean buildingActive = false;

    public static void setUseActive(boolean useActive) {
        IndexPluginsSupport.useActive = useActive;
    }

    static {
        Path curPath = Paths.get(CurrentPath);
        Path indexPath = curPath.resolve(Const.indexDir);
        Path tempPath = curPath.resolve(Const.tempDir);
        // MKDIR TEMP DIR
        if (!Files.exists(tempPath)) {
            try {
                Files.createDirectories(tempPath);
            } catch (Exception ignored) {
            }
        }
        // MKDIR INDEX DIR
        if (!Files.exists(indexPath)) {
            try {
                Files.createDirectories(indexPath);
            } catch (Exception ignored) {
            }
        }
    }

    public static List<File> getJarAnalyzerPluginsSupportAllFiles() {
        return FileUtil.loopFiles(TempPath, pathname -> pathname.getName().endsWith(".class"));
    }

    /**
     * 清除生成标识
     *
     * @param file jar文件
     * @return code
     */
    public static String getCode(File file) {
        String decompile = decompile(file);
        return StrUtil.isNotBlank(decompile)
                ? removeFernflowerPrefix(decompile) : null;
    }

    private static String decompile(File file) {
        String decompile = null;
        try {
            decompile = DecompileEngine.decompile(file.toPath());
        } catch (Exception e) {
            LogUtil.error(e.getMessage());
        }
        return decompile;
    }

    private static String removeFernflowerPrefix(String source) {
        String prefix = DecompileEngine.getFERN_PREFIX();
        return source.startsWith(prefix)
                ? source.substring(prefix.length()) : source;
    }


    public static boolean addIndex(File file) {
        if (useActive || buildingActive) {
            return true;
        }
        synchronized (ACTIVE_BUILD_LOCK) {
            // Recheck after acquiring the lock. An active build may have
            // started after the fast-path check above.
            if (useActive || buildingActive) {
                return true;
            }
            Map<String, String> codeMap = new HashMap<>();
            String code = getCode(file);
            if (StrUtil.isNotBlank(code)) {
                codeMap.put(file.getPath(), code);
            }

            try {
                IndexEngine.initIndex(codeMap);
                logger.info("add index {} ok", FileUtil.getName(file));
                LuceneBuildListener.usePass = true;
                return true;
            } catch (IOException ex) {
                logger.error("add index error: {}", ex.getMessage());
                return false;
            }
        }
    }

    public static boolean initIndex() throws IOException, InterruptedException {
        List<File> jarAnalyzerPluginsSupportAllFiles = getJarAnalyzerPluginsSupportAllFiles();
        if (jarAnalyzerPluginsSupportAllFiles.isEmpty()) {
            LogUtil.info("未找到任何 class 文件 无法搜索");
            return false;
        }

        List<ClassFileEntity> classFiles = new ArrayList<>();
        for (File file : jarAnalyzerPluginsSupportAllFiles) {
            classFiles.add(new ClassFileEntity(
                    file.getPath(), file.toPath(), null));
        }
        IndexBuildResult result = buildActiveIndex(
                classFiles, Paths.get(Const.tempDir));
        return result.isIndexBuilt();
    }

    /**
     * Builds the active index from the exact class set selected for the current
     * analysis and exports the same decompilation results to the sources
     * directory. No second source-export decompilation pass is performed.
     */
    public static IndexBuildResult initIndexAndExportSources(
            List<ClassFileEntity> classFiles, Path tempDir)
            throws IOException, InterruptedException {
        return buildActiveIndex(classFiles, tempDir);
    }

    private static IndexBuildResult buildActiveIndex(
            List<ClassFileEntity> input, Path tempDir)
            throws IOException, InterruptedException {
        synchronized (ACTIVE_BUILD_LOCK) {
            List<ClassFileEntity> classFiles = validClassFiles(input);
            if (classFiles.isEmpty()) {
                LogUtil.info("未找到任何 class 文件 无法搜索");
                return new IndexBuildResult(
                        0, 0, 0, 0, 0, 0, 0, false);
            }

            buildingActive = true;
            useActive = false;
            try {
                DecompileEngine.cleanCache();
                IndexEngine.resetIndex();
                FileUtil.del(DocumentPath);
                Files.createDirectories(Paths.get(DocumentPath));

                AtomicInteger decompiled = new AtomicInteger();
                AtomicInteger exported = new AtomicInteger();
                AtomicInteger indexed = new AtomicInteger();
                AtomicInteger decompileFailed = new AtomicInteger();
                AtomicInteger exportFailed = new AtomicInteger();
                AtomicInteger indexFailed = new AtomicInteger();

                List<List<ClassFileEntity>> split = CollUtil.split(
                        classFiles, MAX_SIZE_GROUP);
                CountDownLatch latch = new CountDownLatch(split.size());
                for (List<ClassFileEntity> files : split) {
                    executorService.execute(() -> {
                        Map<String, String> codeMap = new HashMap<>();
                        try {
                            for (ClassFileEntity classFile : files) {
                                String source = decompile(
                                        classFile.getPath().toFile());
                                if (StrUtil.isBlank(source)) {
                                    decompileFailed.incrementAndGet();
                                    continue;
                                }

                                decompiled.incrementAndGet();
                                if (SourceExportUtil.export(
                                        classFile, source, tempDir)) {
                                    exported.incrementAndGet();
                                } else {
                                    exportFailed.incrementAndGet();
                                }
                                codeMap.put(classFile.getPath()
                                                .toAbsolutePath().normalize()
                                                .toString(),
                                        removeFernflowerPrefix(source));
                            }

                            try {
                                indexed.addAndGet(
                                        IndexEngine.addToIndex(codeMap));
                            } catch (IOException | RuntimeException e) {
                                indexFailed.addAndGet(codeMap.size());
                                logger.error("add active index batch failed: {}",
                                        e.toString());
                            }
                        } finally {
                            latch.countDown();
                        }
                    });
                }

                latch.await();
                boolean committed = false;
                try {
                    IndexEngine.commitIndex();
                    committed = true;
                } catch (IOException | RuntimeException e) {
                    indexFailed.addAndGet(indexed.getAndSet(0));
                    logger.error("commit active index failed: {}", e.toString());
                }

                IndexBuildResult result = new IndexBuildResult(
                        classFiles.size(), decompiled.get(), exported.get(),
                        indexed.get(), decompileFailed.get(),
                        exportFailed.get(), indexFailed.get(), committed);
                if (result.isIndexBuilt()) {
                    useActive = true;
                }
                return result;
            } finally {
                buildingActive = false;
            }
        }
    }

    private static List<ClassFileEntity> validClassFiles(
            List<ClassFileEntity> input) {
        List<ClassFileEntity> result = new ArrayList<>();
        if (input == null) {
            return result;
        }
        for (ClassFileEntity classFile : input) {
            if (classFile != null && classFile.getPath() != null
                    && Files.isRegularFile(classFile.getPath())
                    && classFile.getPath().getFileName().toString()
                    .endsWith(".class")) {
                result.add(classFile);
            }
        }
        return result;
    }

    public static Result search(String keyword) throws IOException {
        return IndexEngine.searchNormal(keyword);
    }

    public static final class IndexBuildResult {
        private final int total;
        private final int decompiled;
        private final int exported;
        private final int indexed;
        private final int decompileFailed;
        private final int exportFailed;
        private final int indexFailed;
        private final boolean committed;

        private IndexBuildResult(int total, int decompiled, int exported,
                                 int indexed, int decompileFailed,
                                 int exportFailed, int indexFailed,
                                 boolean committed) {
            this.total = total;
            this.decompiled = decompiled;
            this.exported = exported;
            this.indexed = indexed;
            this.decompileFailed = decompileFailed;
            this.exportFailed = exportFailed;
            this.indexFailed = indexFailed;
            this.committed = committed;
        }

        public int getTotal() {
            return total;
        }

        public int getDecompiled() {
            return decompiled;
        }

        public int getExported() {
            return exported;
        }

        public int getIndexed() {
            return indexed;
        }

        public int getFailed() {
            return decompileFailed + exportFailed + indexFailed;
        }

        public int getDecompileFailed() {
            return decompileFailed;
        }

        public int getExportFailed() {
            return exportFailed;
        }

        public int getIndexFailed() {
            return indexFailed;
        }

        public boolean isIndexBuilt() {
            return committed && indexed > 0;
        }
    }
}
