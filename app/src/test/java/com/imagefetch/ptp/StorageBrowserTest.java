package com.imagefetch.ptp;

import static com.imagefetch.testutil.Bytes.concat;
import static com.imagefetch.testutil.Bytes.ptpString;
import static com.imagefetch.testutil.Bytes.u16;
import static com.imagefetch.testutil.Bytes.u32;
import static com.imagefetch.testutil.Bytes.zeros;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.imagefetch.testutil.Bytes;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 存储卡浏览契约：用内存伪 PTP 相机模型驱动真实的 {@link StorageBrowser}，
 * 覆盖完整扫描、快速分页、DCF 两层目录布局、扁平布局、页边界与句柄缓存。
 */
public class StorageBrowserTest {
    private static final long STORAGE_ID = 0x10000001L;
    private static final long ROOT = 0xffff_ffffL;

    /** 内存伪相机：目录树 + ObjectInfo 数据集；句柄越大越新。 */
    private static final class FakeCard {
        private final Map<Long, List<Long>> children = new LinkedHashMap<>();
        private final Map<Long, byte[]> objectInfo = new LinkedHashMap<>();
        private final List<Long> roots = new ArrayList<>();
        private final Map<Integer, Integer> calls = new LinkedHashMap<>();

        long folder(long handle, String name, long parent) {
            objectInfo.put(handle, dataset(name, true));
            if (parent == ROOT) roots.add(handle);
            else children.computeIfAbsent(parent, key -> new ArrayList<>()).add(handle);
            return handle;
        }

        long file(long handle, String name, long parent) {
            objectInfo.put(handle, dataset(name, false));
            children.computeIfAbsent(parent, key -> new ArrayList<>()).add(handle);
            return handle;
        }

        int callCount(int operation) {
            return calls.getOrDefault(operation, 0);
        }

        StorageBrowser.Invoker invoker() {
            return (operation, parameters) -> {
                calls.merge(operation, 1, Integer::sum);
                switch (operation) {
                    case StorageBrowser.GET_STORAGE_IDS:
                        return concat(u32(1), u32(STORAGE_ID));
                    case StorageBrowser.GET_STORAGE_INFO:
                        return concat(
                                u16(3), u16(0), u16(1),
                                u32(0), u32(0), u32(1000), u32(0),
                                ptpString("Internal"), ptpString("SD"));
                    case StorageBrowser.GET_OBJECT_HANDLES: {
                        List<Long> handles = handles(parameters[2]);
                        byte[] payload = new byte[4 + handles.size() * 4];
                        Bytes.putU32(payload, 0, handles.size());
                        for (int index = 0; index < handles.size(); index++) {
                            Bytes.putU32(payload, 4 + index * 4, handles.get(index));
                        }
                        return payload;
                    }
                    case StorageBrowser.GET_OBJECT_INFO:
                        return objectInfo.get(parameters[0]);
                    default:
                        throw new IllegalArgumentException(
                                "未预期的操作 0x" + Integer.toHexString(operation));
                }
            };
        }

        private List<Long> handles(long parent) {
            if (parent == ROOT) return roots;
            return children.getOrDefault(parent, new ArrayList<>());
        }

        /** ObjectInfo 数据集：associationType@42 区分目录/文件。 */
        private static byte[] dataset(String name, boolean isFolder) {
            return concat(
                    u32(STORAGE_ID),
                    u16(isFolder ? 0x3001 : 0x3801),
                    u16(0),
                    u32(isFolder ? 0 : 1024),
                    zeros(14),
                    u32(6000),
                    u32(4000),
                    zeros(8),
                    u16(isFolder ? 1 : 0),
                    zeros(8),
                    ptpString(name),
                    ptpString("20260101T120000"));
        }
    }

    /** DCF 布局：DCIM/100CANON 与 DCIM/101CANON，每目录 filesPerFolder 张。 */
    private static FakeCard dcfCard(int filesPerFolder) {
        FakeCard card = new FakeCard();
        long dcim = card.folder(1L, "DCIM", ROOT);
        long folderA = card.folder(2L, "100CANON", dcim);
        long folderB = card.folder(3L, "101CANON", dcim);
        for (int index = 0; index < filesPerFolder; index++) {
            card.file(100L + index, String.format("A_%03d.JPG", index), folderA);
        }
        for (int index = 0; index < filesPerFolder; index++) {
            card.file(200L + index, String.format("B_%03d.JPG", index), folderB);
        }
        return card;
    }

    @Test
    public void fullScanFindsEveryFileInNestedFolders() throws Exception {
        CameraStorage.Snapshot snapshot = StorageBrowser.loadAll(dcfCard(25).invoker(), 0);

        assertEquals("1 个存储卷", 1, snapshot.volumes.size());
        assertEquals("两个目录共 50 张", 50, snapshot.items.size());
    }

    @Test
    public void fastPageReadsOnlyTheCurrentPage() throws Exception {
        FakeCard fullCard = dcfCard(25);
        StorageBrowser.loadAll(fullCard.invoker(), 0);
        int fullScanInfoCalls = fullCard.callCount(StorageBrowser.GET_OBJECT_INFO);

        FakeCard fastCard = dcfCard(25);
        CameraStorage.Page first =
                StorageBrowser.loadPage(fastCard.invoker(), 20, 0, new HashMap<>());
        int fastPageInfoCalls = fastCard.callCount(StorageBrowser.GET_OBJECT_INFO);

        assertEquals(20, first.items.size());
        assertEquals("总数来自句柄计数", 50, first.totalCount);
        assertEquals(3, first.pageCount());
        assertFalse(first.complete);
        assertTrue(
                "快速分页的 ObjectInfo 调用必须远少于完整扫描：" + fastPageInfoCalls
                        + " vs " + fullScanInfoCalls,
                fastPageInfoCalls * 2 < fullScanInfoCalls);
    }

    @Test
    public void handleCacheMakesPageTurnsCheap() throws Exception {
        FakeCard card = dcfCard(25);
        Map<Long, List<Long>> cache = new HashMap<>();
        StorageBrowser.loadPage(card.invoker(), 20, 0, cache);
        int afterFirstPage = card.callCount(StorageBrowser.GET_OBJECT_HANDLES);

        StorageBrowser.loadPage(card.invoker(), 20, 1, cache);

        assertEquals(
                "翻页不应重新遍历目录",
                afterFirstPage,
                card.callCount(StorageBrowser.GET_OBJECT_HANDLES));
    }

    @Test
    public void lastPageHoldsTheRemainder() throws Exception {
        CameraStorage.Page last =
                StorageBrowser.loadPage(dcfCard(25).invoker(), 20, 2, new HashMap<>());

        assertEquals(10, last.items.size());
        assertTrue(last.hasPrevious());
        assertFalse(last.hasNext());
    }

    @Test
    public void pageBeyondTheEndIsEmptyNotAnError() throws Exception {
        CameraStorage.Page page =
                StorageBrowser.loadPage(dcfCard(5).invoker(), 20, 7, new HashMap<>());

        assertTrue(page.items.isEmpty());
        assertEquals(10, page.totalCount);
        assertFalse(page.hasNext());
    }

    @Test
    public void filesSittingDirectlyUnderAFolderAreStillFound() throws Exception {
        // 非 DCF 扁平布局：根目录下直接放文件（一级即文件）。
        FakeCard card = new FakeCard();
        long dcim = card.folder(1L, "DCIM", ROOT);
        for (int index = 0; index < 12; index++) {
            card.file(50L + index, String.format("FLAT_%02d.JPG", index), dcim);
        }

        CameraStorage.Page page =
                StorageBrowser.loadPage(card.invoker(), 20, 0, new HashMap<>());

        assertEquals("扁平布局也必须全部找到", 12, page.totalCount);
        assertEquals(12, page.items.size());
    }

    @Test
    public void theNewestPageContainsTheLargestHandles() throws Exception {
        CameraStorage.Page page =
                StorageBrowser.loadPage(dcfCard(3).invoker(), 3, 0, new HashMap<>());

        List<String> names = new ArrayList<>();
        for (CameraStorage.Item item : page.items) names.add(item.filename);
        // 句柄 200/201/202 最大 → 属于“最新一页”。
        assertTrue(names.contains("B_000.JPG"));
        assertTrue(names.contains("B_001.JPG"));
        assertTrue(names.contains("B_002.JPG"));
    }

    @Test
    public void pageSlicingFromAFullScanIsConsistent() {
        List<CameraStorage.Item> all = new ArrayList<>();
        for (int index = 0; index < 25; index++) {
            all.add(new CameraStorage.Item(
                    index, 1L, 0x3801, "IMG_" + index + ".JPG", 10L, 1, 1, "2026", false));
        }

        CameraStorage.Page page = CameraStorage.Page.ofItems(all, 20, 1);

        assertTrue(page.complete);
        assertEquals(5, page.items.size());
        assertEquals(25, page.totalCount);
        assertEquals(2, page.pageCount());
        assertEquals(20L, page.items.get(0).handle);
    }
}
