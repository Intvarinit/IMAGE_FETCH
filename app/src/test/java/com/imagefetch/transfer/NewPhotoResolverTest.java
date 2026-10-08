package com.imagefetch.transfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.imagefetch.ptp.CameraStorage;
import com.imagefetch.ptp.PtpOperationService;
import com.imagefetch.testutil.FakeCameraTransport;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** “拍后新对象”发现契约：事件优先 + 句柄快照差集回退 + 去重。 */
public class NewPhotoResolverTest {
    private static final long TIMEOUT_MS = 400L;
    private static final long POLL_MS = 10L;

    @Test
    public void findsObjectsAddedAfterTheBaselineByPolling() throws Exception {
        FakeCameraTransport transport = new FakeCameraTransport()
                .snapshot(snapshot(item(1, "OLD.JPG"), item(2, "NEW.JPG")));
        NewPhotoResolver resolver = new NewPhotoResolver("CamA", key -> false);
        resolver.recordBaseline(snapshot(item(1, "OLD.JPG")));

        NewPhotoResolver.Resolution result = resolve(resolver, transport, null);

        assertFalse("无事件时应标记为轮询差集", result.discoveredByEvent);
        assertEquals(1, result.newItems.size());
        assertEquals("NEW.JPG", result.newItems.get(0).filename);
    }

    @Test
    public void eventCandidatesArePreferredAndReported() throws Exception {
        FakeCameraTransport transport = new FakeCameraTransport()
                .snapshot(snapshot(item(1, "OLD.JPG"), item(2, "NEW.JPG")));
        NewPhotoResolver resolver = new NewPhotoResolver("CamA", key -> false);
        resolver.recordBaseline(snapshot(item(1, "OLD.JPG")));
        resolver.onObjectAdded(1L, 2L);

        NewPhotoResolver.Resolution result = resolve(resolver, transport, null);

        assertTrue("事件通道发现必须被标记", result.discoveredByEvent);
        assertEquals(1, result.newItems.size());
        assertEquals("NEW.JPG", result.newItems.get(0).filename);
    }

    @Test
    public void alreadyImportedObjectsAreExcluded() throws Exception {
        FakeCameraTransport transport = new FakeCameraTransport()
                .snapshot(snapshot(item(1, "OLD.JPG"), item(2, "NEW.JPG")));
        Set<String> imported = new HashSet<>();
        imported.add("CamA:1:2");
        NewPhotoResolver resolver = new NewPhotoResolver("CamA", imported::contains);
        resolver.recordBaseline(snapshot(item(1, "OLD.JPG")));

        NewPhotoResolver.Resolution result = resolve(resolver, transport, null);

        assertTrue("已入库对象不得重复发现", result.isEmpty());
    }

    @Test
    public void filterExcludesUnsupportedFormats() throws Exception {
        FakeCameraTransport transport = new FakeCameraTransport()
                .snapshot(snapshot(item(1, "OLD.JPG"), rawItem(2, "NEW.CR3")));
        NewPhotoResolver resolver = new NewPhotoResolver("CamA", key -> false);
        resolver.recordBaseline(snapshot(item(1, "OLD.JPG")));

        NewPhotoResolver.Resolution result =
                resolve(resolver, transport, CameraStorage.Item::isJpeg);

        assertTrue("RAW 应被 JPEG 过滤器排除", result.isEmpty());
    }

    @Test
    public void rawAndJpegAreBothReportedWhenNoFilterIsApplied() throws Exception {
        FakeCameraTransport transport = new FakeCameraTransport()
                .snapshot(snapshot(
                        item(1, "OLD.JPG"),
                        item(2, "NEW.JPG"),
                        rawItem(3, "NEW.CR3")));
        NewPhotoResolver resolver = new NewPhotoResolver("CamA", key -> false);
        resolver.recordBaseline(snapshot(item(1, "OLD.JPG")));

        NewPhotoResolver.Resolution result = resolve(resolver, transport, null);

        assertEquals(2, result.newItems.size());
    }

    @Test
    public void timesOutWithoutNewObjects() throws Exception {
        FakeCameraTransport transport = new FakeCameraTransport()
                .snapshot(snapshot(item(1, "OLD.JPG")));
        NewPhotoResolver resolver = new NewPhotoResolver("CamA", key -> false);
        resolver.recordBaseline(snapshot(item(1, "OLD.JPG")));

        long started = System.currentTimeMillis();
        NewPhotoResolver.Resolution result = resolve(resolver, transport, null);
        long elapsed = System.currentTimeMillis() - started;

        assertTrue(result.isEmpty());
        assertTrue("必须等到超时才返回，实际 " + elapsed + " ms", elapsed >= TIMEOUT_MS - 100);
    }

    @Test
    public void cancellationInterruptsTheWait() throws Exception {
        FakeCameraTransport transport = new FakeCameraTransport()
                .snapshot(snapshot(item(1, "OLD.JPG")));
        NewPhotoResolver resolver = new NewPhotoResolver("CamA", key -> false);
        resolver.recordBaseline(snapshot(item(1, "OLD.JPG")));
        TransferControl control = new TransferControl();
        control.cancel("用户取消");

        try {
            resolve(resolver, transport, null, control);
            org.junit.Assert.fail("取消必须中断等待");
        } catch (InterruptedException expected) {
            // 预期路径。
        }
    }

    // ---------- resolveBatch：机内连续拍摄一次快门出多张 ----------

    @Test
    public void batchCollectsUntilExpectedCountReached() throws Exception {
        FakeCameraTransport transport = new FakeCameraTransport().stageSnapshots(
                snapshot(item(1, "OLD.JPG")),
                snapshot(item(1, "OLD.JPG"), item(2, "A.JPG")),
                snapshot(item(1, "OLD.JPG"), item(2, "A.JPG"), item(3, "B.JPG")),
                snapshot(item(1, "OLD.JPG"), item(2, "A.JPG"), item(3, "B.JPG"), item(4, "C.JPG")));
        NewPhotoResolver resolver = new NewPhotoResolver("CamA", key -> false);
        resolver.recordBaseline(snapshot(item(1, "OLD.JPG")));

        long started = System.currentTimeMillis();
        NewPhotoResolver.Resolution result = resolveBatch(resolver, transport, 3, 5000L, 5000L);
        long elapsed = System.currentTimeMillis() - started;

        assertEquals(3, result.newItems.size());
        assertEquals("A.JPG", result.newItems.get(0).filename);
        assertEquals("B.JPG", result.newItems.get(1).filename);
        assertEquals("C.JPG", result.newItems.get(2).filename);
        assertFalse(result.discoveredByEvent);
        assertTrue("收满即应返回，不该等 settle/超时，实际 " + elapsed + " ms", elapsed < 3000);
    }

    @Test
    public void batchStopsAfterSettleWhenCameraDeliversFewer() throws Exception {
        FakeCameraTransport transport = new FakeCameraTransport().stageSnapshots(
                snapshot(item(1, "OLD.JPG")),
                snapshot(item(1, "OLD.JPG"), item(2, "A.JPG")));
        NewPhotoResolver resolver = new NewPhotoResolver("CamA", key -> false);
        resolver.recordBaseline(snapshot(item(1, "OLD.JPG")));

        long started = System.currentTimeMillis();
        NewPhotoResolver.Resolution result = resolveBatch(resolver, transport, 5, 120L, 5000L);
        long elapsed = System.currentTimeMillis() - started;

        assertEquals("相机只出 1 张就交 1 张，不硬凑数量", 1, result.newItems.size());
        assertEquals("A.JPG", result.newItems.get(0).filename);
        assertTrue("应在 settle 内收手，而非等到超时，实际 " + elapsed + " ms", elapsed < 2500);
    }

    @Test
    public void batchDoesNotCollectTheSameObjectTwice() throws Exception {
        FakeCameraTransport transport = new FakeCameraTransport().stageSnapshots(
                snapshot(item(1, "OLD.JPG"), item(2, "A.JPG")),
                snapshot(item(1, "OLD.JPG"), item(2, "A.JPG")));
        NewPhotoResolver resolver = new NewPhotoResolver("CamA", key -> false);
        resolver.recordBaseline(snapshot(item(1, "OLD.JPG")));

        NewPhotoResolver.Resolution result = resolveBatch(resolver, transport, 5, 80L, 5000L);

        assertEquals("同一对象后续每轮都判为新，却只能收一次", 1, result.newItems.size());
        assertEquals("A.JPG", result.newItems.get(0).filename);
    }

    private static NewPhotoResolver.Resolution resolveBatch(
            NewPhotoResolver resolver,
            FakeCameraTransport transport,
            int expectedCount,
            long settleMillis,
            long timeoutMillis) throws Exception {
        return resolver.resolveBatch(
                new PtpOperationService(transport), timeoutMillis, POLL_MS, 0,
                expectedCount, settleMillis, null, new TransferControl());
    }

    private static NewPhotoResolver.Resolution resolve(
            NewPhotoResolver resolver,
            FakeCameraTransport transport,
            java.util.function.Predicate<CameraStorage.Item> filter) throws Exception {
        return resolve(resolver, transport, filter, new TransferControl());
    }

    private static NewPhotoResolver.Resolution resolve(
            NewPhotoResolver resolver,
            FakeCameraTransport transport,
            java.util.function.Predicate<CameraStorage.Item> filter,
            TransferControl control) throws Exception {
        return resolver.resolveUntil(
                new PtpOperationService(transport), TIMEOUT_MS, POLL_MS, 0, filter, control);
    }

    @Test
    public void recentLimitIsForwardedToTheCardEnumeration() throws Exception {
        FakeCameraTransport transport = new FakeCameraTransport()
                .snapshot(snapshot(item(1, "OLD.JPG"), item(2, "NEW.JPG")));
        NewPhotoResolver resolver = new NewPhotoResolver("CamA", key -> false);
        resolver.recordBaseline(snapshot(item(1, "OLD.JPG")));

        resolver.resolveUntil(
                new PtpOperationService(transport), TIMEOUT_MS, POLL_MS, 20, null, new TransferControl());

        assertEquals("“最近 N 张”必须透传到存储卡枚举", 20, transport.lastMaxItems);
    }

    private static CameraStorage.Item item(long handle, String filename) {
        return new CameraStorage.Item(
                handle, 1L, 0x3801, filename, 100L, 6000, 4000, "2026-01-01 12:00:00", false);
    }

    private static CameraStorage.Item rawItem(long handle, String filename) {
        return new CameraStorage.Item(
                handle, 1L, 0xB103, filename, 100L, 6000, 4000, "2026-01-01 12:00:00", false);
    }

    private static CameraStorage.Snapshot snapshot(CameraStorage.Item... items) {
        List<CameraStorage.Item> list = new ArrayList<>(Arrays.asList(items));
        return new CameraStorage.Snapshot(new ArrayList<>(), list);
    }
}
