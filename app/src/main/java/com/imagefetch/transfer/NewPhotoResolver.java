package com.imagefetch.transfer;

import com.imagefetch.ptp.CameraStorage;
import com.imagefetch.ptp.PtpOperationService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 发现“刚刚拍摄产生的相机对象”：事件优先（PTP ObjectAdded 0x4002），
 * 事件缺失/延迟时回退到“拍摄前句柄快照差集”的有上限轮询。
 *
 * <p>去重键为 {@code (相机身份, 存储卷, 对象句柄)}，不依赖“列表第一张/时间最新”，
 * 也不假设一次拍摄只生成一个对象（RAW+JPEG 会有多个）。
 */
public final class NewPhotoResolver {
    /** 一次解析结果。 */
    public static final class Resolution {
        public final List<CameraStorage.Item> newItems;
        public final boolean discoveredByEvent;

        Resolution(List<CameraStorage.Item> newItems, boolean discoveredByEvent) {
            this.newItems = newItems;
            this.discoveredByEvent = discoveredByEvent;
        }

        public boolean isEmpty() {
            return newItems.isEmpty();
        }
    }

    private final String cameraIdentity;
    private final Predicate<String> alreadyImported;
    private final Set<String> baselineKeys = new LinkedHashSet<>();
    private final Set<Long> eventHandles = new LinkedHashSet<>();

    public NewPhotoResolver(String cameraIdentity, Predicate<String> alreadyImported) {
        this.cameraIdentity = cameraIdentity == null ? "" : cameraIdentity;
        this.alreadyImported = alreadyImported != null ? alreadyImported : key -> false;
    }

    /** 拍摄前调用：记录句柄快照作为差集基线，并清空上一轮事件候选。 */
    public synchronized void recordBaseline(CameraStorage.Snapshot snapshot) {
        baselineKeys.clear();
        eventHandles.clear();
        if (snapshot == null) return;
        for (CameraStorage.Item item : snapshot.items) {
            baselineKeys.add(objectKey(item));
        }
    }

    /** 相机上报新增对象（event reader 线程调用）。 */
    public synchronized void onObjectAdded(long storageId, long objectHandle) {
        eventHandles.add(objectHandle);
    }

    public synchronized int pendingEventCount() {
        return eventHandles.size();
    }

    public synchronized int baselineSize() {
        return baselineKeys.size();
    }

    /**
     * 事件优先 + 轮询差集，直到发现新增对象或超时。
     * 每轮都对候选执行 ObjectInfo 已解析结果（快照内已含存储卷/名/格式/时间/尺寸），
     * 再按 {@code filter} 与去重键筛选。
     */
    public Resolution resolveUntil(
            PtpOperationService operations,
            long timeoutMillis,
            long pollIntervalMillis,
            int maxItems,
            Predicate<CameraStorage.Item> filter,
            TransferControl control) throws Exception {
        // 单张拍摄：发现第一张就返回，与旧行为完全一致（settle=0 时下面第一轮命中即 break）。
        return resolveBatch(
                operations, timeoutMillis, pollIntervalMillis, maxItems, 1, 0L, filter, control);
    }

    /**
     * 等「一批」新增对象：机内连续拍摄（对焦包围、HDR 包围）按一次快门会产出多张，
     * 而 {@link #resolveUntil} 发现第一张就返回，只能拿到头一张——后面的还在写卡里。
     *
     * <p>收满 {@code expectedCount} 张即返回；不足时等 {@code settleMillis} 内不再有新对象
     * 就收手（不硬凑数量，相机实际出几张就交几张），再不行才等到 {@code timeoutMillis}。
     *
     * @param expectedCount 预计张数；≤1 时行为与 {@link #resolveUntil} 相同
     * @param settleMillis  已收到对象后，再等多久没有新增就认为相机写完了；0 表示不做这个等待
     */
    public Resolution resolveBatch(
            PtpOperationService operations,
            long timeoutMillis,
            long pollIntervalMillis,
            int maxItems,
            int expectedCount,
            long settleMillis,
            Predicate<CameraStorage.Item> filter,
            TransferControl control) throws Exception {
        int target = Math.max(1, expectedCount);
        long settleNanos = Math.max(0L, settleMillis) * 1_000_000L;
        long deadline = System.nanoTime() + Math.max(1L, timeoutMillis) * 1_000_000L;
        boolean eventSeen = false;
        // 按对象键去重并保序：同一张会在后续每一轮里被判为「新」，不能重复收。
        Map<String, CameraStorage.Item> collected = new LinkedHashMap<>();
        long lastProgressAt = System.nanoTime();
        while (true) {
            if (control != null) control.throwIfCancelled();

            boolean hasEventCandidate;
            synchronized (this) {
                hasEventCandidate = !eventHandles.isEmpty();
            }

            CameraStorage.Snapshot snapshot = operations.snapshot(maxItems);
            List<CameraStorage.Item> matches = new ArrayList<>();
            if (hasEventCandidate) {
                eventSeen = true;
                Set<Long> handles;
                synchronized (this) {
                    handles = new LinkedHashSet<>(eventHandles);
                    eventHandles.clear();
                }
                for (long handle : handles) {
                    CameraStorage.Item item = PtpOperationService.findItem(snapshot, handle);
                    if (isNew(item, filter)) matches.add(item);
                }
            } else {
                for (CameraStorage.Item item : snapshot.items) {
                    if (isNew(item, filter)) matches.add(item);
                }
            }
            int before = collected.size();
            for (CameraStorage.Item item : matches) {
                collected.putIfAbsent(objectKey(item), item);
            }
            if (collected.size() > before) lastProgressAt = System.nanoTime();

            if (collected.size() >= target) break;

            long now = System.nanoTime();
            if (!collected.isEmpty() && now - lastProgressAt >= settleNanos) break;
            long remaining = deadline - now;
            if (remaining <= 0) break;
            sleep(Math.min(pollIntervalMillis, remaining / 1_000_000L));
        }
        return new Resolution(new ArrayList<>(collected.values()), eventSeen);
    }

    private boolean isNew(CameraStorage.Item item, Predicate<CameraStorage.Item> filter) {
        if (item == null) return false;
        String key = objectKey(item);
        synchronized (this) {
            if (baselineKeys.contains(key)) return false;
        }
        if (alreadyImported.test(key)) return false;
        return filter == null || filter.test(item);
    }

    private String objectKey(CameraStorage.Item item) {
        return item.objectKey(cameraIdentity);
    }

    private static void sleep(long millis) throws InterruptedException {
        long value = Math.max(1L, millis);
        Thread.sleep(value);
    }
}
