package com.imagefetch.transfer;

/**
 * 心跳与自动重连策略（纯逻辑，无 Android 依赖，便于契约测试）。
 * 数值对齐 ZENCHE docs/PTPIP_PROTOCOL.md §9 的 B2 策略。
 */
public final class ReconnectPolicy {
    /** 保活周期：每 5s 在 event 通道发一次 ProbeRequest。 */
    public static final long HEARTBEAT_INTERVAL_MS = 5000L;
    /** 连续失败阈值：3 次无响应判离线。 */
    public static final int OFFLINE_THRESHOLD = 3;

    /**
     * 退避序列：1/2/4/8/15/25/30/30s（累计约 115s，封顶 30s）。
     * 相机侧连接槽位的释放超时窗口通常长于原先的 ~31s，窗口拉到 ~115s 后可覆盖相机
     * 自行回收槽位的时间，多数情况下无需用户去相机删连接即可自愈。
     */
    private static final long[] BACKOFF_MS =
            {1000, 2000, 4000, 8000, 15000, 25000, 30000, 30000};

    /** 第 attempt 次重连（从 1 开始）的退避毫秒数。 */
    public static long backoffDelayMs(int attempt) {
        int index = Math.min(Math.max(attempt, 1), BACKOFF_MS.length) - 1;
        return BACKOFF_MS[index];
    }

    private ReconnectPolicy() {}
}
