package com.imagefetch.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link CaptureWaitPolicy} 的契约测试。
 *
 * <p>这些常量直接决定「遥控一次快门能收回几张」：settle 太小会在相机还在写下一张时提前收手，
 * 总预算太小会让 8 张吃满超时只收回前几张。把数值钉在测试里，改常量时至少会红一次。
 */
public class CaptureWaitPolicyTest {
    @Test
    public void 单张不做settle等待() {
        assertEquals("单张发现第一张即返回，不额外等", 0L, CaptureWaitPolicy.settleMillisFor(1));
    }

    @Test
    public void 多张批次settle为六秒() {
        assertEquals(CaptureWaitPolicy.BATCH_SETTLE_MS, CaptureWaitPolicy.settleMillisFor(2));
        assertEquals(CaptureWaitPolicy.BATCH_SETTLE_MS, CaptureWaitPolicy.settleMillisFor(8));
        assertEquals("settle 只需覆盖单张间隔，不随张数增长",
                CaptureWaitPolicy.settleMillisFor(8), CaptureWaitPolicy.settleMillisFor(2));
    }

    @Test
    public void 八张预算为五十四秒() {
        assertEquals(54000L, CaptureWaitPolicy.budgetMillisFor(8));
    }

    @Test
    public void 小张数预算不低于单张默认超时() {
        // 2 张按公式是 2×6 s + 6 s = 18 s，但预算有「不低于单张默认超时 20 s」的下限，
        // 所以取 20 s。下限保证再小的批次也不会比单张更紧。
        assertEquals(CaptureWaitPolicy.DEFAULT_DISCOVERY_TIMEOUT_MS, CaptureWaitPolicy.budgetMillisFor(2));
        // 3 张起按张数增长：3×6 s + 6 s = 24 s，已高于下限。
        assertEquals(24000L, CaptureWaitPolicy.budgetMillisFor(3));
    }

    @Test
    public void 单张预算沿用默认超时() {
        assertEquals(CaptureWaitPolicy.DEFAULT_DISCOVERY_TIMEOUT_MS, CaptureWaitPolicy.budgetMillisFor(1));
    }

    @Test
    public void 手填大张数由上限封顶() {
        assertEquals(CaptureWaitPolicy.TIMEOUT_CAP_MS, CaptureWaitPolicy.budgetMillisFor(999));
    }

    @Test
    public void 非法张数按一张处理() {
        assertEquals(0L, CaptureWaitPolicy.settleMillisFor(0));
        assertEquals(CaptureWaitPolicy.DEFAULT_DISCOVERY_TIMEOUT_MS, CaptureWaitPolicy.budgetMillisFor(0));
        assertEquals(CaptureWaitPolicy.DEFAULT_DISCOVERY_TIMEOUT_MS, CaptureWaitPolicy.budgetMillisFor(-5));
    }

    @Test
    public void 预算随张数单调不减() {
        long previous = 0L;
        for (int shots = 1; shots <= 30; shots++) {
            long budget = CaptureWaitPolicy.budgetMillisFor(shots);
            assertTrue("张数增加预算不该变小：shots=" + shots, budget >= previous);
            assertTrue("预算不得超过上限", budget <= CaptureWaitPolicy.TIMEOUT_CAP_MS);
            previous = budget;
        }
    }
}
