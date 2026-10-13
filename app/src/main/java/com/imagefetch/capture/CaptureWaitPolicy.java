package com.imagefetch.capture;

/**
 * 「一次快门产出多张」时，收图等待窗口的计算规则。
 *
 * <p>单张拍摄沿用「发现第一张就返回」；机内对焦包围 / HDR 包围一次快门会连续产出多张，
 * 每张之间有「移焦 + 写 2600 万像素卡」的间隔，收图必须等到相机真的写完了才收手，
 * 否则会把后面的张数丢掉（实测 8 张只收到 4 张）。
 *
 * <p>{@link #settleMillisFor(int)} 给「收够张数之外还等多久没有新对象才算写完」，
 * {@link #budgetMillisFor(int)} 给整个批次的总兜底超时。这段判断纯靠算术，不碰 Android，
 * 所以能直接单测。
 */
public final class CaptureWaitPolicy {
    /** 单张拍摄沿用原有超时。 */
    public static final long DEFAULT_DISCOVERY_TIMEOUT_MS = 20000L;
    /**
     * 多张批次里，最后一张之后还等这么久没有新对象才认为相机写完了。
     * 只需覆盖<b>单张间隔</b>，不随张数增长。
     */
    public static final long BATCH_SETTLE_MS = 6000L;
    /** 多张批次里每张分摊的等待预算（移焦 + 写卡）。 */
    public static final long PER_SHOT_BUDGET_MS = 6000L;
    /** 多张批次总预算的上限，防手填张数（如 999）把界面拖死。 */
    public static final long TIMEOUT_CAP_MS = 120000L;

    private CaptureWaitPolicy() {
    }

    /** 单张不做 settle 等待（发现第一张即返回）；多张批次等 {@link #BATCH_SETTLE_MS}。 */
    public static long settleMillisFor(int shots) {
        return shots > 1 ? BATCH_SETTLE_MS : 0L;
    }

    /**
     * 总等待预算：单张沿用 {@link #DEFAULT_DISCOVERY_TIMEOUT_MS}；多张按张数分摊并加 settle，
     * 再以 {@link #TIMEOUT_CAP_MS} 封顶。
     *
     * <p>收满即返回是主路径，这个预算只在相机真的慢时兜底，不会拖慢正常批次。
     */
    public static long budgetMillisFor(int shots) {
        int clamped = Math.max(1, shots);
        long scaled = (long) clamped * PER_SHOT_BUDGET_MS + settleMillisFor(clamped);
        return Math.min(TIMEOUT_CAP_MS, Math.max(DEFAULT_DISCOVERY_TIMEOUT_MS, scaled));
    }
}
