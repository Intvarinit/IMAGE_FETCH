package com.imagefetch.ptp;

import static com.imagefetch.testutil.Bytes.concat;
import static com.imagefetch.testutil.Bytes.ptpString;
import static com.imagefetch.testutil.Bytes.u16;
import static com.imagefetch.testutil.Bytes.u32;
import static com.imagefetch.testutil.Bytes.u64;
import static com.imagefetch.testutil.Bytes.zeros;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** 标准 PTP 数据集解析契约（字段偏移与端口实现保持一致）。 */
public class CameraStorageTest {
    @Test
    public void parsesStorageIds() {
        byte[] data = concat(u32(2), u32(0x10000001L), u32(0x10000002L));
        List<Long> ids = CameraStorage.parseStorageIds(data);
        assertEquals(Arrays.asList(0x10000001L, 0x10000002L), ids);
    }

    @Test
    public void storageIdParsingIsClampedToAvailableBytes() {
        // 声明 100 个，实际只够 1 个：必须按可用字节数收敛，不能越界。
        byte[] data = concat(u32(100), u32(7));
        assertEquals(Arrays.asList(7L), CameraStorage.parseStorageIds(data));
    }

    @Test
    public void storageIdParsingHandlesMissingData() {
        assertTrue(CameraStorage.parseStorageIds(null).isEmpty());
        assertTrue(CameraStorage.parseStorageIds(new byte[0]).isEmpty());
    }

    @Test
    public void objectHandlesShareTheStorageArrayLayout() {
        byte[] data = concat(u32(1), u32(0x2001));
        assertEquals(Arrays.asList(0x2001L), CameraStorage.parseObjectHandles(data));
    }

    @Test
    public void parsesStorageInfoWithCapacityAndLabel() {
        byte[] description = ptpString("Internal");
        byte[] label = ptpString("SD");
        byte[] data = concat(
                u16(3),          // storageType
                u16(0),          // 解析器未使用的保留位
                u16(0x001F),     // accessCapability（非 0 = 只读）
                u64(64_000_000_000L),
                u64(12_345L),
                u32(999),
                description,
                label);
        CameraStorage.Volume volume = CameraStorage.parseStorageInfo(0x10000001L, data);
        assertEquals("SD", volume.name);
        assertEquals(64_000_000_000L, volume.capacityBytes);
        assertEquals(12_345L, volume.freeBytes);
        assertEquals(999L, volume.freeImages);
        assertTrue(volume.readOnly);
    }

    @Test
    public void storageInfoFallsBackWhenTheDatasetIsTooShort() {
        CameraStorage.Volume volume = CameraStorage.parseStorageInfo(0x10000002L, new byte[4]);
        assertEquals(0L, volume.capacityBytes);
        assertFalse(volume.readOnly);
        assertTrue(volume.name.contains("10000002"));
    }

    @Test
    public void parsesObjectInfo() {
        byte[] data = objectInfoDataset(
                0x10000001L, 0x3801, 123456L, 6000, 4000, 0,
                "IMG_0001.JPG", "20260101T120000");
        CameraStorage.Item item = CameraStorage.parseObjectInfo(0x2001, data);
        assertNotNull(item);
        assertEquals(0x2001L, item.handle);
        assertEquals(0x10000001L, item.storageId);
        assertEquals(0x3801, item.format);
        assertEquals(123456L, item.sizeBytes);
        assertEquals(6000, item.width);
        assertEquals(4000, item.height);
        assertEquals("IMG_0001.JPG", item.filename);
        assertEquals("2026-01-01 12:00:00", item.capturedAt);
        assertEquals(0L, item.parentHandle);
        assertTrue(item.isJpeg());
        assertFalse(item.isRaw());
        assertFalse(item.isVideo());
    }

    @Test
    public void parsesParentHandleForCompanionLookup() {
        byte[] data = objectInfoDataset(
                0x10000001L, 0x3801, 123456L, 6000, 4000, 0,
                "IMG_0001.JPG", "20260101T120000", 0x2005L);
        CameraStorage.Item item = CameraStorage.parseObjectInfo(0x2001, data);
        assertNotNull(item);
        assertEquals(0x2005L, item.parentHandle);
    }

    @Test
    public void companionJpegMatchesBaseNameStorageAndFolder() {
        CameraStorage.Item raw = new CameraStorage.Item(
                5, 9, 0, "IMG_0001.CR3", 1, 0, 0, "—", false, 77);
        assertTrue(raw.hasCompanionJpeg(new CameraStorage.Item(
                6, 9, 0, "IMG_0001.JPG", 1, 0, 0, "—", false, 77)));
        assertFalse("主文件名不同", raw.hasCompanionJpeg(new CameraStorage.Item(
                7, 9, 0, "IMG_0002.JPG", 1, 0, 0, "—", false, 77)));
        assertFalse("存储卷不同", raw.hasCompanionJpeg(new CameraStorage.Item(
                8, 10, 0, "IMG_0001.JPG", 1, 0, 0, "—", false, 77)));
        assertFalse("目录不同", raw.hasCompanionJpeg(new CameraStorage.Item(
                9, 9, 0, "IMG_0001.JPG", 1, 0, 0, "—", false, 88)));
        assertFalse("同扩展名不是 JPG", raw.hasCompanionJpeg(new CameraStorage.Item(
                10, 9, 0, "IMG_0001.CR3", 1, 0, 0, "—", false, 77)));
    }

    @Test
    public void associationObjectsAreNotTreatedAsFiles() {
        byte[] data = objectInfoDataset(
                1, 0x3001, 0, 0, 0, 1, "100NIKON", "20260101T120000");
        assertTrue(CameraStorage.isAssociation(data));
        assertNull(CameraStorage.parseObjectInfo(0x2002, data));
    }

    @Test
    public void objectsWithoutAFilenameAreRejected() {
        byte[] data = objectInfoDataset(1, 0x3801, 0, 0, 0, 0, "", "20260101T120000");
        assertNull(CameraStorage.parseObjectInfo(0x2003, data));
    }

    @Test
    public void shortDatasetsAreRejected() {
        assertNull(CameraStorage.parseObjectInfo(1, new byte[10]));
        assertNull(CameraStorage.parseObjectInfo(1, null));
        assertFalse(CameraStorage.isAssociation(new byte[10]));
        assertFalse(CameraStorage.isAssociation(null));
    }

    @Test
    public void itemFormatClassificationAndObjectKey() {
        CameraStorage.Item raw =
                new CameraStorage.Item(5, 9, 0, "IMG.CR3", 1, 0, 0, "—", false);
        assertTrue(raw.isRaw());
        assertFalse(raw.isJpeg());
        assertEquals("CamA:9:5", raw.objectKey("CamA"));

        CameraStorage.Item video =
                new CameraStorage.Item(6, 9, 0, "CLIP.MP4", 1, 0, 0, "—", false);
        assertTrue(video.isVideo());
        assertFalse(video.isJpeg());

        CameraStorage.Item photo =
                new CameraStorage.Item(7, 9, 0, "IMG.JPEG", 1, 0, 0, "—", false);
        assertTrue(photo.isJpeg());
        assertFalse(photo.isRaw());
    }

    @Test
    public void newestHandlesKeepsTheLargestHandles() {
        List<Long> handles = Arrays.asList(5L, 99L, 12L, 41L);
        assertEquals(
                "取最新 = 取句柄最大的几个（句柄单调递增假设）",
                Arrays.asList(99L, 41L),
                CameraStorage.newestHandles(handles, 2));
    }

    @Test
    public void newestHandlesWithoutLimitReturnsEverything() {
        List<Long> handles = Arrays.asList(5L, 99L, 12L);
        assertEquals(handles, CameraStorage.newestHandles(handles, 0));
        assertEquals(handles, CameraStorage.newestHandles(handles, -1));
        assertEquals(handles, CameraStorage.newestHandles(handles, 10));
    }

    @Test
    public void newestHandlesHandlesEmptyInput() {
        assertTrue(CameraStorage.newestHandles(null, 20).isEmpty());
        assertTrue(CameraStorage.newestHandles(new java.util.ArrayList<>(), 20).isEmpty());
    }

    /**
     * ObjectInfo 数据集：storageId@0、format@4、protection@6、size@8、
     * width@26、height@30、associationType@42、filename@52、captureDate 紧随其后。
     */
    private static byte[] objectInfoDataset(
            long storageId,
            int format,
            long size,
            int width,
            int height,
            int associationType,
            String filename,
            String capturedAt) {
        return objectInfoDataset(
                storageId, format, size, width, height, associationType, filename, capturedAt, 0L);
    }

    /**
     * ObjectInfo 数据集：storageId@0、format@4、protection@6、size@8、
     * width@26、height@30、bitDepth@34、parentObject@38、associationType@42、
     * filename@52、captureDate 紧随其后。
     */
    private static byte[] objectInfoDataset(
            long storageId,
            int format,
            long size,
            int width,
            int height,
            int associationType,
            String filename,
            String capturedAt,
            long parentHandle) {
        return concat(
                u32(storageId),
                u16(format),
                u16(0),
                u32(size),
                zeros(14),
                u32(width),
                u32(height),
                u32(0),
                u32(parentHandle),
                u16(associationType),
                zeros(8),
                ptpString(filename),
                ptpString(capturedAt));
    }
}
