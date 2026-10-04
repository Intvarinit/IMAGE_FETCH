package com.imagefetch.transfer;

/**
 * 连续心跳失败计数：达到阈值即判定离线并复位计数。
 * 判定逻辑与 Handler/线程模型解耦，便于契约测试。
 */
public final class HeartbeatMonitor {
    private final int offlineThreshold;
    private int missed;

    public HeartbeatMonitor(int offlineThreshold) {
        this.offlineThreshold = Math.max(1, offlineThreshold);
    }

    /** 记录一次探测结果；返回 true 表示应判定离线（计数已复位）。 */
    public boolean recordProbeResult(boolean alive) {
        if (alive) {
            missed = 0;
            return false;
        }
        missed++;
        if (missed >= offlineThreshold) {
            missed = 0;
            return true;
        }
        return false;
    }

    public void reset() {
        missed = 0;
    }

    public int missedCount() {
        return missed;
    }
}
