package com.imagefetch.capture;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一批「对焦包围」落盘文件的收集器，供景深合成使用。
 *
 * <p>为什么要单独一个类：{@code Listener.onFileSaved} 对每一次落盘都会回调——单张拍摄、
 * 手动下载、批量下载都走它。景深合成只关心「本次对焦包围这一批」，所以必须有明确的
 * <b>收集窗口</b>：拍摄前 {@link #arm()} 开窗，批次结束 {@link #disarm()} 关窗，
 * 窗口外的文件一律不进来。这段判断逻辑纯靠 Java 集合，不碰 Android，所以能直接单测。
 *
 * <p>收集的是<b>应用内的副本路径</b>，不是相册条目：导出相册后会否删掉副本由协调器决定
 * （见 {@code CameraSessionCoordinator#setRetainLocalCopiesForStack}）。
 */
public final class FocusStackBatch {
    /** 少于这个张数就不值得合成——单张无景深可合。 */
    public static final int MIN_SHOTS = 2;

    private final List<File> collected = new ArrayList<>();
    private boolean collecting;

    /** 开窗：清空上一次的结果并开始收集。每次拍摄前调用。 */
    public void arm() {
        collected.clear();
        collecting = true;
    }

    /** 关窗：停止收集。已收集的快照仍然保留，供合成本身使用。 */
    public void disarm() {
        collecting = false;
    }

    /** 收一张。窗口外（或空引用）直接忽略——避免把别的落盘文件混进来。 */
    public void add(File file) {
        if (!collecting || file == null) return;
        collected.add(file);
    }

    /** 当前已收集文件的快照，按落盘顺序。 */
    public List<File> files() {
        return Collections.unmodifiableList(new ArrayList<>(collected));
    }

    public int size() {
        return collected.size();
    }

    /** 是否够张数做合成。 */
    public boolean ready() {
        return collected.size() >= MIN_SHOTS;
    }
}
