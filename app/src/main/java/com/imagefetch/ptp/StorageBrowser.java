package com.imagefetch.ptp;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 存储卡浏览：完整扫描与快速分页共用同一套 PTP 操作序列。
 *
 * <p>通过 {@link Invoker} 注入具体传输（PTP/IP 或 USB），因此本类不依赖任何
 * Android/网络类型，可脱离真机做契约测试。
 */
public final class StorageBrowser {
    public static final int GET_STORAGE_IDS = 0x1004;
    public static final int GET_STORAGE_INFO = 0x1005;
    public static final int GET_OBJECT_HANDLES = 0x1007;
    public static final int GET_OBJECT_INFO = 0x1008;
    private static final long ROOT_ASSOCIATION = 0xffff_ffffL;

    /** 单次 PTP data-in 操作。 */
    @FunctionalInterface
    public interface Invoker {
        byte[] invoke(int operation, long[] parameters) throws Exception;
    }

    /**
     * 快速分页：只列句柄 + 只对当前页取 ObjectInfo。
     *
     * <p>句柄枚举做浅层目录探测（见 {@link #collectFileHandles}），句柄列表按
     * 存储卷缓存在 {@code handleCache} 里，翻页时不再重新遍历。
     *
     * <p>跨目录统一按句柄降序排列作为“最新在前”：相机通常在全卡范围内单调
     * 分配句柄（TBC-awaiting-hardware）；若不满足，页内顺序会与拍摄时间不符。
     */
    public static CameraStorage.Page loadPage(
            Invoker invoker,
            int pageSize,
            int pageIndex,
            Map<Long, List<Long>> handleCache) throws Exception {
        int size = Math.max(1, pageSize);
        int index = Math.max(0, pageIndex);

        List<Long> orderedHandles = new ArrayList<>();
        for (Long storageId : storageIds(invoker)) {
            List<Long> handles = handleCache == null ? null : handleCache.get(storageId);
            if (handles == null) {
                handles = collectFileHandles(invoker, storageId);
                if (handleCache != null) handleCache.put(storageId, handles);
            }
            orderedHandles.addAll(handles);
        }
        orderedHandles.sort((left, right) -> Long.compare(right, left));

        int total = orderedHandles.size();
        int from = Math.min(index * size, total);
        int to = Math.min(from + size, total);
        List<CameraStorage.Item> items = new ArrayList<>();
        for (Long handle : orderedHandles.subList(from, to)) {
            byte[] info = invoker.invoke(GET_OBJECT_INFO, new long[]{handle});
            if (CameraStorage.isAssociation(info)) continue; // 假设不成立时也不把它当文件
            CameraStorage.Item item = CameraStorage.parseObjectInfo(handle, info);
            if (item != null) items.add(item);
        }
        sortNewestFirst(items);
        return new CameraStorage.Page(items, index, size, total, false);
    }

    /**
     * 浅层文件句柄枚举（快速模式）。
     *
     * <p>根层对象逐个取 ObjectInfo 判定目录；一级子对象同样逐个判定
     * （DCF 布局下这一级就是 <code>100CANON</code> 这类子目录，数量很少）；
     * 二级对象直接视为文件，不再逐个取 ObjectInfo。
     *
     * <p>假设：目录层级不超过 <code>存储卡 / DCIM / 子目录 / 文件</code> 三层
     * （DCF 标准布局）。更深的嵌套需要走“完整扫描”兜底。
     */
    static List<Long> collectFileHandles(Invoker invoker, long storageId) throws Exception {
        List<Long> files = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        for (long rootHandle : handles(invoker, storageId, ROOT_ASSOCIATION)) {
            if (!visited.add(rootHandle)) continue;
            byte[] rootInfo = invoker.invoke(GET_OBJECT_INFO, new long[]{rootHandle});
            if (!CameraStorage.isAssociation(rootInfo)) {
                files.add(rootHandle);
                continue;
            }
            for (long child : handles(invoker, storageId, rootHandle)) {
                if (!visited.add(child)) continue;
                byte[] childInfo = invoker.invoke(GET_OBJECT_INFO, new long[]{child});
                if (CameraStorage.isAssociation(childInfo)) {
                    // 二级视为文件。
                    for (long grandChild : handles(invoker, storageId, child)) {
                        if (visited.add(grandChild)) files.add(grandChild);
                    }
                } else {
                    files.add(child);
                }
            }
        }
        return files;
    }

    /** 完整扫描：任意深度递归，逐对象取 ObjectInfo。 */
    public static CameraStorage.Snapshot loadAll(Invoker invoker, int maxItems) throws Exception {
        List<CameraStorage.Volume> volumes = new ArrayList<>();
        List<CameraStorage.Item> items = new ArrayList<>();
        for (Long storageId : storageIds(invoker)) {
            volumes.add(CameraStorage.parseStorageInfo(
                    storageId,
                    invoker.invoke(GET_STORAGE_INFO, new long[]{storageId})));
            appendAll(invoker, storageId, items, maxItems);
        }
        sortNewestFirst(items);
        if (maxItems > 0 && items.size() > maxItems) {
            items = new ArrayList<>(items.subList(0, maxItems));
        }
        return new CameraStorage.Snapshot(volumes, items);
    }

    private static void appendAll(
            Invoker invoker, long storageId, List<CameraStorage.Item> items, int maxItems)
            throws Exception {
        ArrayDeque<Long> pending = new ArrayDeque<>(handles(invoker, storageId, ROOT_ASSOCIATION));
        Set<Long> visited = new HashSet<>();
        while (!pending.isEmpty()) {
            long handle = pending.removeFirst();
            if (!visited.add(handle)) continue;
            byte[] info = invoker.invoke(GET_OBJECT_INFO, new long[]{handle});
            if (CameraStorage.isAssociation(info)) {
                List<Long> children = new ArrayList<>(handles(invoker, storageId, handle));
                // 目录内按“句柄越大越新”裁剪，把 ObjectInfo 调用量从整卡文件数降到 maxItems。
                if (maxItems > 0 && children.size() > maxItems) {
                    children = CameraStorage.newestHandles(children, maxItems);
                }
                for (long child : children) {
                    if (!visited.contains(child)) pending.addLast(child);
                }
                continue;
            }
            CameraStorage.Item item = CameraStorage.parseObjectInfo(handle, info);
            if (item != null) items.add(item);
        }
    }

    /** 相机返回的存储卷列表。 */
    public static List<Long> storageIds(Invoker invoker) throws Exception {
        return CameraStorage.parseStorageIds(invoker.invoke(GET_STORAGE_IDS, new long[0]));
    }

    private static List<Long> handles(Invoker invoker, long storageId, long parent)
            throws Exception {
        return CameraStorage.parseObjectHandles(
                invoker.invoke(GET_OBJECT_HANDLES, new long[]{storageId, 0, parent}));
    }

    private static void sortNewestFirst(List<CameraStorage.Item> items) {
        items.sort((left, right) -> {
            int byDate = right.capturedAt.compareTo(left.capturedAt);
            return byDate != 0 ? byDate : right.filename.compareToIgnoreCase(left.filename);
        });
    }

    private StorageBrowser() {}
}
