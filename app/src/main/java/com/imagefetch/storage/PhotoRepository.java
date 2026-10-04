/*
 * Adapted from ZENCHE (https://github.com/Tauber01/ZENCHE) native/android
 * CaptureWorkflow.java, MIT License, Copyright (c) 2026 Tauber01.
 * See the NOTICE file in the project root.
 *
 * Changes for IMAGE_FETCH:
 *   - 面向“拍后取回原文件”的单文件落盘：.part 暂存 → 长度校验 → 原子 rename；
 *   - 增加按 (相机身份, 存储卷, 对象句柄) 的已导入索引，用于会话内/跨会话去重；
 *   - 移除 ZENCHE 的 XMP sidecar、双备份、地理标记与延时/焦点合成等产品状态。
 */
package com.imagefetch.storage;

import android.content.Context;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;

/** 应用文件库中的原图落盘、校验、原子提交与去重索引。 */
public final class PhotoRepository {
    /** 一次下载的暂存/目标文件。 */
    public static final class StagedFile {
        public final File staging;
        public final File destination;

        StagedFile(File staging, File destination) {
            this.staging = staging;
            this.destination = destination;
        }
    }

    private static final String INDEX_FILENAME = ".imported-index";
    private static final String NAMING_TEMPLATE = "{date}_{camera}_{counter}";

    private final File outputDirectory;
    private final File indexFile;
    private final Set<String> importedKeys = new HashSet<>();
    private int counter = 1;

    public PhotoRepository(Context context) {
        this(new File(context.getFilesDir(), "captures"));
    }

    public PhotoRepository(File outputDirectory) {
        this.outputDirectory = outputDirectory;
        this.indexFile = new File(outputDirectory, INDEX_FILENAME);
        loadIndex();
    }

    public File outputDirectory() {
        return outputDirectory;
    }

    /** 已导入对象身份（跨会话持久）快照。 */
    public synchronized Set<String> importedKeys() {
        return Collections.unmodifiableSet(new HashSet<>(importedKeys));
    }

    /** 该相机对象是否已经入库（重复事件/轮询的兜底去重）。 */
    public synchronized boolean isImported(String objectKey) {
        return objectKey != null && importedKeys.contains(objectKey);
    }

    /** 预留一个暂存文件与目标文件名（目标名唯一，不覆盖既有文件）。 */
    public StagedFile stage(String originalFilename, String cameraName) throws Exception {
        ensureDirectory();
        String base;
        synchronized (this) {
            base = reserveBaseName(cameraName);
        }
        String extension = extension(originalFilename);
        File destination;
        synchronized (this) {
            destination = uniqueFile(outputDirectory, base, extension);
        }
        File staging = new File(
                outputDirectory,
                "." + destination.getName() + "." + System.nanoTime() + ".part");
        return new StagedFile(staging, destination);
    }

    public OutputStream openStaging(File staging) throws Exception {
        return new BufferedOutputStream(new FileOutputStream(staging));
    }

    /**
     * 校验长度 → fsync → 原子 rename → 记录去重索引。
     * expectedBytes &lt;= 0 表示相机未声明长度，按实际收到字节数提交。
     */
    public void commit(StagedFile staged, String objectKey, long expectedBytes)
            throws Exception {
        if (!staged.staging.isFile()) {
            throw new Exception("临时文件不存在，无法提交。");
        }
        long length = staged.staging.length();
        if (expectedBytes > 0 && length != expectedBytes) {
            throw new Exception(
                    "文件长度与相机声明不一致：声明 " + expectedBytes + " 字节，实际 "
                            + length + " 字节");
        }
        if (length <= 0) {
            throw new Exception("相机返回了空文件。");
        }
        try (RandomAccessFile sync = new RandomAccessFile(staged.staging, "rw")) {
            sync.getFD().sync();
        }
        if (staged.destination.exists() && !staged.destination.delete()) {
            throw new Exception("无法覆盖同名目标文件。");
        }
        if (!staged.staging.renameTo(staged.destination)) {
            copy(staged.staging, staged.destination);
            if (!staged.staging.delete()) {
                throw new Exception("无法清理下载临时文件。");
            }
        }
        markImported(objectKey, staged.destination.getName());
    }

    public void discard(StagedFile staged) {
        if (staged == null || staged.staging == null) return;
        staged.staging.delete();
    }

    public synchronized void markImported(String objectKey, String filename) {
        if (objectKey == null || objectKey.isEmpty()) return;
        if (!importedKeys.add(objectKey)) return;
        try {
            ensureDirectory();
            try (FileOutputStream output = new FileOutputStream(indexFile, true)) {
                output.write((objectKey + "\t" + filename + "\n")
                        .getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
        } catch (Exception ignored) {
            // 索引写入失败不影响文件已成功落盘；下次会话可能重复发现该对象。
        }
    }

    public synchronized List<String> indexedFilenames() {
        List<String> result = new ArrayList<>();
        if (!indexFile.isFile()) return result;
        try {
            for (String line : readLines(indexFile)) {
                int tab = line.indexOf('\t');
                if (tab >= 0 && tab + 1 < line.length()) {
                    result.add(line.substring(tab + 1));
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    // ── 内部 ──

    private void loadIndex() {
        if (!indexFile.isFile()) return;
        try {
            for (String line : readLines(indexFile)) {
                int tab = line.indexOf('\t');
                String key = tab >= 0 ? line.substring(0, tab) : line;
                if (!key.trim().isEmpty()) importedKeys.add(key.trim());
            }
        } catch (Exception ignored) {
        }
    }

    private static List<String> readLines(File file) throws Exception {
        List<String> lines = new ArrayList<>();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(
                        new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) lines.add(line);
            }
        }
        return lines;
    }

    private void ensureDirectory() throws Exception {
        if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
            throw new Exception("无法创建保存目录：" + outputDirectory.getAbsolutePath());
        }
    }

    private String reserveBaseName(String cameraName) {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA)
                .format(new Date());
        String value = NAMING_TEMPLATE
                .replace("{date}", stamp)
                .replace("{camera}", cameraName == null ? "Camera" : cameraName)
                .replace("{counter}", String.format(Locale.ROOT, "%04d", counter));
        counter++;
        return sanitize(value);
    }

    private static File uniqueFile(File directory, String base, String extension) {
        File result = new File(directory, base + "." + extension);
        int suffix = 2;
        while (result.exists()) {
            result = new File(directory, base + "_" + suffix + "." + extension);
            suffix++;
        }
        return result;
    }

    private static void copy(File source, File destination) throws Exception {
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) output.write(buffer, 0, count);
            }
            output.getFD().sync();
        }
    }

    private static String extension(String filename) {
        int dot = filename == null ? -1 : filename.lastIndexOf('.');
        String value = dot >= 0 ? filename.substring(dot + 1) : "jpg";
        value = value.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        return value.isEmpty() ? "jpg" : value;
    }

    private static String sanitize(String value) {
        String result = value == null ? "" : value.replaceAll("[\\\\/:?%*|\"<>]", "_").trim();
        if (result.isEmpty()) result = "IMAGE_FETCH";
        return result.length() > 120 ? result.substring(0, 120) : result;
    }
}
