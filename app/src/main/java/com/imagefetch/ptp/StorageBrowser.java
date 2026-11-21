package com.imagefetch.ptp;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
     * 快速分页的会话缓存：按存储卷缓存句柄列表，外加「仅 JPG / 照片」的筛查结果。
     * 卡内结构变化后（拍摄、删除、换卡）必须 {@link #clear()}。
     */
    public static final class PageCache {
        final Map<Long, List<Long>> handles = new LinkedHashMap<>();
        FilteredIndex jpegIndex;
        FilteredIndex photoIndex;

        public void clear() {
            handles.clear();
            jpegIndex = null;
            photoIndex = null;
        }
    }

    /** 筛查目标：仅 JPEG，或 JPEG + RAW（照片）。 */
    private static final int KIND_JPEG = 0;
    private static final int KIND_PHOTO = 1;

    /**
     * 按扩展名筛出的对象索引：把「句柄越大越新」的全量句柄序列按需筛查成目标格式序列。
     *
     * <p>格式只能从 ObjectInfo 的文件名判断，所以按格式分页必须逐个取 ObjectInfo；
     * 但只在需要时往后扫（要第 N 页才扫到第 N 页所需的量），扫描结果缓存，
     * 因此顺序往后翻只扫一遍，且页内对象在筛查时就已解析好，取页不再额外往返。
     */
    private static final class FilteredIndex {
        final List<Long> orderedHandles;
        final List<CameraStorage.Item> items = new ArrayList<>();
        final int kind;
        int scanned;
        boolean scannedAll;

        FilteredIndex(List<Long> orderedHandles, int kind) {
            this.orderedHandles = orderedHandles;
            this.kind = kind;
        }

        boolean matches(CameraStorage.Item item) {
            if (kind == KIND_JPEG) return item.isJpeg();
            return item.isRaw() || item.isJpeg();
        }

        boolean hasUnscanned() {
            return scanned < orderedHandles.size();
        }
    }

    /**
     * 快速分页：只列句柄 + 只对当前页取 ObjectInfo。
     *
     * <p>句柄枚举做浅层目录探测（见 {@link #collectFileHandles}），句柄列表按
     * 存储卷缓存在 {@code cache} 里，翻页时不再重新遍历。
     *
     * <p>跨目录统一按句柄降序排列作为“最新在前”：相机通常在全卡范围内单调
     * 分配句柄（TBC-awaiting-hardware）；若不满足，页内顺序会与拍摄时间不符。
     *
     * <p>{@code jpegOnly=true} 时分页范围就是 JPG 本身：每页仍取 {@code pageSize} 张 JPG
     * （不再掺入 RAW/视频把页面撑空），总数也是 JPG 张数。
     */
    public static CameraStorage.Page loadPage(
            Invoker invoker,
            int pageSize,
            int pageIndex,
            boolean jpegOnly,
            PageCache cache) throws Exception {
        int size = Math.max(1, pageSize);
        int index = Math.max(0, pageIndex);
        PageCache store = cache == null ? new PageCache() : cache;

        List<Long> orderedHandles = orderedHandles(invoker, store);
        if (!jpegOnly) {
            return allObjectPage(invoker, orderedHandles, size, index);
        }
        return filteredPage(invoker, orderedHandles, store, size, index, KIND_JPEG);
    }

    /**
     * 按照片分页：JPG 与 RAW 都算（视频除外）。胶片模拟页既支持相机 JPEG，
     * 也支持 RAW 内嵌预览，所以列表要同时给出两种来源。
     */
    public static CameraStorage.Page loadPhotoPage(
            Invoker invoker,
            int pageSize,
            int pageIndex,
            PageCache cache) throws Exception {
        int size = Math.max(1, pageSize);
        int index = Math.max(0, pageIndex);
        PageCache store = cache == null ? new PageCache() : cache;
        return filteredPage(
                invoker, orderedHandles(invoker, store), store, size, index, KIND_PHOTO);
    }

    /** 全部对象（含 RAW/视频）分页：总数即句柄计数。 */
    private static CameraStorage.Page allObjectPage(
            Invoker invoker, List<Long> orderedHandles, int size, int index) throws Exception {
        int total = orderedHandles.size();
        int from = Math.min(index * size, total);
        int to = Math.min(from + size, total);
        List<CameraStorage.Item> items = new ArrayList<>();
        for (Long handle : orderedHandles.subList(from, to)) {
            CameraStorage.Item item = objectInfo(invoker, handle);
            if (item != null) items.add(item);
        }
        sortNewestFirst(items);
        return new CameraStorage.Page(items, index, size, total, false, false);
    }

    /**
     * 「仅 JPG」/「照片」分页：先把句柄序列筛查成目标格式序列，再按该序列切片，
     * 因此每页都是实打实的 {@code size} 个对象。
     */
    private static CameraStorage.Page filteredPage(
            Invoker invoker, List<Long> orderedHandles, PageCache store,
            int size, int index, int kind) throws Exception {
        FilteredIndex filtered = kind == KIND_JPEG ? store.jpegIndex : store.photoIndex;
        // 句柄列表变了（新增/删除/换卡）就重扫：旧索引里的切片位置已对不上。
        if (filtered == null || !filtered.orderedHandles.equals(orderedHandles)) {
            filtered = new FilteredIndex(new ArrayList<>(orderedHandles), kind);
            if (kind == KIND_JPEG) store.jpegIndex = filtered;
            else store.photoIndex = filtered;
        }
        // 扫到“够本页用”为止：少于这个量下一页必然拿不满。
        int needed = (index + 1) * size;
        // 「照片」页的筛查量设上限：若卡里几乎都是视频/其它对象，不能一口气把几千个对象
        // 全查一遍，否则相机链路会被长时间占住。没查完的部分由 moreUnscanned 标记，下一页
        // 继续扫。仅 JPG 的列表仍要全扫，因为它的总数会直接展示给用户。
        int scanBudget = kind == KIND_JPEG ? Integer.MAX_VALUE : size * 6;
        int examined = 0;
        while (filtered.items.size() < needed && filtered.hasUnscanned()) {
            if (examined >= scanBudget) break;
            examined++;
            long handle = filtered.orderedHandles.get(filtered.scanned++);
            CameraStorage.Item item = objectInfo(invoker, handle);
            if (item != null && filtered.matches(item)) filtered.items.add(item);
        }
        filtered.scannedAll = !filtered.hasUnscanned();

        int total = filtered.items.size();
        // 指定的页可能已不存在（上一页筛查后才发现没那么多对象）：退回最后一页而不是给空页。
        int safeIndex = index > 0 && index * size >= total ? Math.max(0, (total - 1) / size) : index;
        int from = Math.min(safeIndex * size, total);
        int to = Math.min(from + size, total);
        List<CameraStorage.Item> items = new ArrayList<>(filtered.items.subList(from, to));
        sortNewestFirst(items);
        return new CameraStorage.Page(items, safeIndex, size, total, false, !filtered.scannedAll);
    }

    /** 取对象的 ObjectInfo 并解析；目录（关联对象）与解析不出的都当没有。 */
    private static CameraStorage.Item objectInfo(Invoker invoker, long handle) throws Exception {
        byte[] info = invoker.invoke(GET_OBJECT_INFO, new long[]{handle});
        if (CameraStorage.isAssociation(info)) return null;
        return CameraStorage.parseObjectInfo(handle, info);
    }

    /** 全量文件句柄，按“最新在前”排序（句柄大的在前）；句柄列表按存储卷缓存。 */
    private static List<Long> orderedHandles(Invoker invoker, PageCache cache) throws Exception {
        List<Long> orderedHandles = new ArrayList<>();
        for (Long storageId : storageIds(invoker)) {
            List<Long> handles = cache.handles.get(storageId);
            if (handles == null) {
                handles = collectFileHandles(invoker, storageId);
                cache.handles.put(storageId, handles);
            }
            orderedHandles.addAll(handles);
        }
        orderedHandles.sort((left, right) -> Long.compare(right, left));
        return orderedHandles;
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

    /**
     * 在 RAW 所在目录里找同名 JPG（RAW+JPG 拍摄）。
     *
     * <p>只枚举同一 ParentObject 下的兄弟对象，不整卡扫描；ParentObject 未知
     * （老实现/机身内存对象）时返回 null，由调用方退回 RAW 显影。
     */
    public static CameraStorage.Item findCompanionJpeg(
            Invoker invoker, CameraStorage.Item raw) throws Exception {
        if (raw == null || !raw.isRaw() || raw.storageId <= 0 || raw.parentHandle <= 0) {
            return null;
        }
        for (long handle : handles(invoker, raw.storageId, raw.parentHandle)) {
            if (handle == raw.handle) continue;
            CameraStorage.Item candidate = objectInfo(invoker, handle);
            if (raw.hasCompanionJpeg(candidate)) return candidate;
        }
        return null;
    }

    /** 完整扫描：任意深度递归，逐对象取 ObjectInfo。 */
    public static CameraStorage.Snapshot loadAll(Invoker invoker, int maxItems) throws Exception {
        List<CameraStorage.Volume> volumes = new ArrayList<>();
        List<CameraStorage.Item> items = new ArrayList<>();
        Map<Long, Long> maxHandles = new LinkedHashMap<>();
        for (Long storageId : storageIds(invoker)) {
            volumes.add(CameraStorage.parseStorageInfo(
                    storageId,
                    invoker.invoke(GET_STORAGE_INFO, new long[]{storageId})));
            appendAll(invoker, storageId, items, maxItems, maxHandles);
        }
        sortNewestFirst(items);
        if (maxItems > 0 && items.size() > maxItems) {
            items = new ArrayList<>(items.subList(0, maxItems));
        }
        return new CameraStorage.Snapshot(volumes, items, maxHandles);
    }

    /**
     * 只列「句柄大于基线最大值」的对象（拍摄收图轮询专用）。
     *
     * <p>完整扫描每轮都会对最新几十张逐个取 ObjectInfo，而拍摄等待时每秒要轮询一次；
     * 这里改成先做浅层句柄枚举（拍照只会在既有 DCF 目录里追加文件），再只对超出基线的
     * 少量新句柄取 ObjectInfo，把每轮往返从「60+ 次」降到「目录数 + 新张数」。
     *
     * <p>依赖相机「句柄单调递增」的通行行为（与 {@link CameraStorage#newestHandles} 同一假设）。
     *
     * @param maxHandles 基线时各存储卷枚举到的最大句柄；缺失的卷按「没有基线」处理
     */
    public static CameraStorage.Snapshot loadNewerThan(
            Invoker invoker, Map<Long, Long> maxHandles, int maxItems) throws Exception {
        int limit = maxItems > 0 ? maxItems : Integer.MAX_VALUE;
        List<CameraStorage.Item> items = new ArrayList<>();
        for (Long storageId : storageIds(invoker)) {
            Long baseline = maxHandles == null ? null : maxHandles.get(storageId);
            long minHandle = baseline == null ? Long.MIN_VALUE : baseline;
            List<Long> fresh = new ArrayList<>();
            for (Long handle : collectFileHandles(invoker, storageId)) {
                if (handle != null && handle > minHandle) fresh.add(handle);
            }
            fresh.sort((left, right) -> Long.compare(right, left));
            int inspected = Math.min(fresh.size(), limit);
            for (int index = 0; index < inspected; index++) {
                CameraStorage.Item item = objectInfo(invoker, fresh.get(index));
                if (item != null) items.add(item);
            }
            if (items.size() >= limit) break;
        }
        sortNewestFirst(items);
        if (items.size() > limit) items = new ArrayList<>(items.subList(0, limit));
        return new CameraStorage.Snapshot(Collections.emptyList(), items);
    }

    private static void appendAll(
            Invoker invoker,
            long storageId,
            List<CameraStorage.Item> items,
            int maxItems,
            Map<Long, Long> maxHandles) throws Exception {
        List<Long> rootHandles = handles(invoker, storageId, ROOT_ASSOCIATION);
        noteMaxHandles(maxHandles, storageId, rootHandles);
        ArrayDeque<Long> pending = new ArrayDeque<>(rootHandles);
        Set<Long> visited = new HashSet<>();
        while (!pending.isEmpty()) {
            long handle = pending.removeFirst();
            if (!visited.add(handle)) continue;
            byte[] info = invoker.invoke(GET_OBJECT_INFO, new long[]{handle});
            if (CameraStorage.isAssociation(info)) {
                List<Long> children = new ArrayList<>(handles(invoker, storageId, handle));
                // 即使裁剪 ObjectInfo 调用量，也要先把整份句柄计入最大值：
                // 下一轮增量枚举据此判断哪些才是拍摄之后新增的对象。
                noteMaxHandles(maxHandles, storageId, children);
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

    /** 记录某存储卷枚举到的最大句柄（目录与文件都算：它们共享同一套句柄空间）。 */
    private static void noteMaxHandles(
            Map<Long, Long> maxHandles, long storageId, List<Long> handles) {
        Long previous = maxHandles.get(storageId);
        long max = previous == null ? Long.MIN_VALUE : previous;
        for (Long handle : handles) {
            if (handle != null && handle > max) max = handle;
        }
        if (max != Long.MIN_VALUE) maxHandles.put(storageId, max);
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
