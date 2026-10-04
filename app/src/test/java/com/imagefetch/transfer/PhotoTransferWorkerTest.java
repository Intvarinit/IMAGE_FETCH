package com.imagefetch.transfer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.imagefetch.ptp.CameraStorage;
import com.imagefetch.ptp.PtpOperationService;
import com.imagefetch.storage.PhotoRepository;
import com.imagefetch.testutil.FakeCameraTransport;
import com.imagefetch.transport.PtpResponseException;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;

/** 传输契约：流式落盘、长度校验、有限重试、取消、去重。 */
public class PhotoTransferWorkerTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private PhotoRepository repository() {
        return new PhotoRepository(folder.getRoot());
    }

    private PhotoTransferWorker worker(FakeCameraTransport transport, PhotoRepository repository) {
        return new PhotoTransferWorker(
                new PtpOperationService(transport), repository, 3, 0L);
    }

    @Test
    public void streamsAndCommitsTheOriginalBytes() throws Exception {
        byte[] payload = new byte[256 * 1024];
        for (int index = 0; index < payload.length; index++) {
            payload[index] = (byte) (index % 251);
        }
        FakeCameraTransport transport = new FakeCameraTransport().objectBytes(payload);
        PhotoRepository repository = repository();

        PhotoTransferWorker.Result result = worker(transport, repository).transfer(
                item(7, "IMG_0007.JPG", payload.length), null, new TransferControl());

        assertFalse(result.alreadyImported);
        assertEquals(1, result.attempts);
        assertEquals(payload.length, result.bytes);
        assertArrayEquals(payload, Files.readAllBytes(result.file.toPath()));
        assertTrue(repository.isImported("Fake Camera:1:7"));
    }

    @Test
    public void declaredLengthMismatchFailsAndLeavesNoArtifacts() throws Exception {
        FakeCameraTransport transport = new FakeCameraTransport().objectBytes(new byte[4]);
        PhotoRepository repository = repository();

        try {
            worker(transport, repository).transfer(
                    item(8, "IMG_0008.JPG", 99L), null, new TransferControl());
            fail("长度不一致必须失败");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("长度"));
        }
        assertEquals("写卡未完成的长度不一致按有限次数重试", 3, transport.downloadCalls);
        assertNoStagingArtifacts();
        assertFalse(repository.isImported("Fake Camera:1:8"));
    }

    @Test
    public void transientDeviceBusyIsRetried() throws Exception {
        byte[] payload = {1, 2, 3, 4};
        FakeCameraTransport transport = new FakeCameraTransport()
                .objectBytes(payload)
                .failDownloads(1, new PtpResponseException(0x2019));

        PhotoTransferWorker.Result result = worker(transport, repository()).transfer(
                item(9, "IMG_0009.JPG", 4L), null, new TransferControl());

        assertEquals(2, result.attempts);
        assertEquals(2, transport.downloadCalls);
        assertArrayEquals(payload, Files.readAllBytes(result.file.toPath()));
    }

    @Test
    public void deterministicRejectionIsNotRetried() throws Exception {
        FakeCameraTransport transport = new FakeCameraTransport()
                .objectBytes(new byte[4])
                .failDownloads(5, new PtpResponseException(0x2009));

        try {
            worker(transport, repository()).transfer(
                    item(10, "IMG_0010.JPG", 4L), null, new TransferControl());
            fail("确定性拒绝必须直接抛出");
        } catch (PtpResponseException expected) {
            assertEquals(0x2009, expected.responseCode);
        }
        assertEquals("非瞬时响应码不得重试", 1, transport.downloadCalls);
    }

    @Test
    public void alreadyImportedObjectsAreSkippedWithoutDownloading() throws Exception {
        FakeCameraTransport transport = new FakeCameraTransport().objectBytes(new byte[4]);
        PhotoRepository repository = repository();
        repository.markImported("Fake Camera:1:11", "existing.jpg");

        PhotoTransferWorker.Result result = worker(transport, repository).transfer(
                item(11, "IMG_0011.JPG", 4L), null, new TransferControl());

        assertTrue(result.alreadyImported);
        assertEquals(0, transport.downloadCalls);
    }

    @Test
    public void cancellationStopsTheTransferBeforeAnyStaging() throws Exception {
        FakeCameraTransport transport = new FakeCameraTransport().objectBytes(new byte[64]);
        TransferControl control = new TransferControl();
        control.cancel("用户取消");

        try {
            worker(transport, repository()).transfer(
                    item(12, "IMG_0012.JPG", 64L), null, control);
            fail("取消必须中断传输");
        } catch (InterruptedException expected) {
            // 预期路径。
        }
        assertEquals(0, transport.downloadCalls);
        assertNoStagingArtifacts();
    }

    @Test
    public void progressIsReportedWithTheDeclaredTotal() throws Exception {
        byte[] payload = new byte[1024];
        FakeCameraTransport transport = new FakeCameraTransport().objectBytes(payload);
        long[] last = {-1, -1};

        worker(transport, repository()).transfer(
                item(13, "IMG_0013.JPG", payload.length),
                (received, total) -> {
                    last[0] = received;
                    last[1] = total;
                },
                new TransferControl());

        assertEquals(payload.length, last[0]);
        assertEquals(payload.length, last[1]);
    }

    @Test
    public void unknownDeclaredSizeAcceptsTheActualLength() throws Exception {
        // 机内内存对象（SDRAM）没有 ObjectInfo，尺寸未知：按实际收到的字节数提交。
        FakeCameraTransport transport = new FakeCameraTransport().objectBytes(new byte[5]);

        PhotoTransferWorker.Result result = worker(transport, repository()).transfer(
                item(14, "CAPTURE_X.JPG", -1L), null, new TransferControl());

        assertEquals(5L, result.bytes);
        assertEquals(1, result.attempts);
    }

    @Test
    public void objectKeyOverrideKeepsRepeatedSdramCapturesDistinct() throws Exception {
        FakeCameraTransport transport = new FakeCameraTransport().objectBytes(new byte[]{1, 2, 3});
        PhotoRepository repository = repository();
        PhotoTransferWorker worker = worker(transport, repository);

        // SDRAM 兜底句柄跨拍摄是同一个值：键带拍摄令牌时两次都必须落盘。
        PhotoTransferWorker.Result first = worker.transfer(
                item(0xffff0001L, "CAPTURE_A.JPG", -1L),
                null,
                new TransferControl(),
                "capture:1:4294901761");
        PhotoTransferWorker.Result second = worker.transfer(
                item(0xffff0001L, "CAPTURE_B.JPG", -1L),
                null,
                new TransferControl(),
                "capture:2:4294901761");

        assertFalse(first.alreadyImported);
        assertFalse(second.alreadyImported);
        assertEquals(2, transport.downloadCalls);

        // 同一拍摄令牌重复请求才是真正的重复对象。
        PhotoTransferWorker.Result repeated = worker.transfer(
                item(0xffff0001L, "CAPTURE_B.JPG", -1L),
                null,
                new TransferControl(),
                "capture:2:4294901761");
        assertTrue(repeated.alreadyImported);
    }

    private static CameraStorage.Item item(long handle, String filename, long size) {
        return new CameraStorage.Item(
                handle, 1L, 0x3801, filename, size, 6000, 4000, "2026-01-01 12:00:00", false);
    }

    private void assertNoStagingArtifacts() {
        File[] files = folder.getRoot().listFiles();
        if (files == null) return;
        for (File file : files) {
            assertFalse("不得留下 .part 半成品：" + file.getName(),
                    file.getName().endsWith(".part"));
        }
    }
}
