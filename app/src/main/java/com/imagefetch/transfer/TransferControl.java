package com.imagefetch.transfer;

/** 传输/发现阶段的协作式取消（UI 线程置位，工作线程轮询）。 */
public final class TransferControl {
    private volatile boolean cancelled;
    private volatile String reason;

    public void cancel(String reason) {
        this.reason = reason;
        this.cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public String reason() {
        return reason;
    }

    public void throwIfCancelled() throws InterruptedException {
        if (cancelled) {
            throw new InterruptedException(
                    reason == null ? "操作已取消" : "操作已取消：" + reason);
        }
    }
}
