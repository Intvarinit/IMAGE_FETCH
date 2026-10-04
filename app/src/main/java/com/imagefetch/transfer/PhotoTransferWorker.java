package com.imagefetch.transfer;

import com.imagefetch.ptp.CameraStorage;
import com.imagefetch.ptp.PtpOperationService;
import com.imagefetch.storage.PhotoRepository;
import com.imagefetch.transport.CameraTransport;
import com.imagefetch.transport.PtpResponseException;

import java.io.File;
import java.io.OutputStream;

/**
 * 单文件可靠传输：流式接收 → 长度校验 → 原子提交；失败丢弃 .part，
 * 对 DeviceBusy / 写卡延迟按有限退避重试，绝不删除相机原件。
 */
public final class PhotoTransferWorker {
    public static final class Result {
        public final File file;
        public final long bytes;
        public final long elapsedMillis;
        public final int attempts;
        public final boolean alreadyImported;

        Result(File file, long bytes, long elapsedMillis, int attempts, boolean alreadyImported) {
            this.file = file;
            this.bytes = bytes;
            this.elapsedMillis = elapsedMillis;
            this.attempts = attempts;
            this.alreadyImported = alreadyImported;
        }
    }

    private final PtpOperationService operations;
    private final PhotoRepository repository;
    private final int maxAttempts;
    private final long backoffBaseMillis;

    public PhotoTransferWorker(PtpOperationService operations, PhotoRepository repository) {
        this(operations, repository, 3, 800);
    }

    public PhotoTransferWorker(
            PtpOperationService operations,
            PhotoRepository repository,
            int maxAttempts,
            long backoffBaseMillis) {
        this.operations = operations;
        this.repository = repository;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.backoffBaseMillis = Math.max(0, backoffBaseMillis);
    }

    public Result transfer(
            CameraStorage.Item item,
            CameraTransport.ProgressListener progress,
            TransferControl control) throws Exception {
        return transfer(item, progress, control, null);
    }

    /**
     * @param objectKeyOverride 去重键覆盖；机内内存拍摄（SDRAM）的句柄可能跨拍摄重复，
     *                          调用方需传入带本次拍摄令牌的键，避免误判为“已入库”。
     */
    public Result transfer(
            CameraStorage.Item item,
            CameraTransport.ProgressListener progress,
            TransferControl control,
            String objectKeyOverride) throws Exception {
        String key = objectKeyOverride != null
                ? objectKeyOverride
                : item.objectKey(operations.cameraIdentity());
        if (repository.isImported(key)) {
            return new Result(null, 0, 0, 0, true);
        }
        long expected = item.sizeBytes > 0 ? item.sizeBytes : -1;
        long startedAt = System.currentTimeMillis();
        Exception lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (control != null) control.throwIfCancelled();
            PhotoRepository.StagedFile staged =
                    repository.stage(item.filename, operations.cameraName());
            try {
                // 必须在提交前关闭暂存流：Windows 上打开中的文件无法 rename/删除。
                long received;
                try (OutputStream output = repository.openStaging(staged.staging)) {
                    received = operations.download(item.handle, output, progress);
                    output.flush();
                }
                repository.commit(staged, key, expected > 0 ? expected : received);
                long length = staged.destination.length();
                return new Result(
                        staged.destination,
                        length,
                        System.currentTimeMillis() - startedAt,
                        attempt,
                        false);
            } catch (InterruptedException cancelled) {
                repository.discard(staged);
                throw cancelled;
            } catch (Exception error) {
                repository.discard(staged);
                lastError = error;
                if (attempt >= maxAttempts || !isRetryable(error)) break;
                sleep(backoffBaseMillis * attempt, control);
            }
        }
        throw lastError != null ? lastError : new Exception("传输失败。");
    }

    /**
     * 可重试判定：PTP 确定性拒绝（如 InvalidObjectHandle）不重试；
     * DeviceBusy/写卡未完成、超时、一般 IO 与长度不一致按有限次数退避重试。
     */
    private static boolean isRetryable(Exception error) {
        if (error instanceof InterruptedException) return false;
        if (error instanceof PtpResponseException) {
            return ((PtpResponseException) error).isTransient();
        }
        return true;
    }

    private static void sleep(long millis, TransferControl control) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Math.max(0, millis);
        while (true) {
            if (control != null) control.throwIfCancelled();
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) return;
            Thread.sleep(Math.min(remaining, 100));
        }
    }
}
