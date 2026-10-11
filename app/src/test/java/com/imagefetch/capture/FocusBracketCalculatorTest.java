package com.imagefetch.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link FocusBracketCalculator} 的契约测试。
 *
 * <p>重点是把两个真实算例逐位钉死——这两个算例是需求方给出的，是整套公式唯一的锚点：
 * 戒指（50mm f/8、0.5–1.5m、重叠 40% → 40 张 / 增量 5）与风光（24mm f/11、1–8m → 8 张 / 增量 10）。
 * 公式里最容易写错的三个点也各有一条：张数用 {@code round} 而非 {@code ceil}、
 * 景深取<b>近端</b>、达到超焦距要单独走分支而不是硬算。
 */
public class FocusBracketCalculatorTest {
    /** 浮点比较容差：公式与手算之间的舍入差。 */
    private static final double TOLERANCE = 0.5;

    @Test
    public void 戒指算例逐位复现() {
        FocusBracketCalculator.Result result =
                FocusBracketCalculator.compute(50, 8, 500, 1500, 40);

        assertEquals(43.1, result.dofMm, TOLERANCE);
        assertEquals(25.9, result.stepMm, TOLERANCE);
        assertEquals(40, result.count);
        assertEquals(5, result.incrementPrimary);
        assertFalse(result.dofInfinite);
        assertEquals(FocusBracketPlan.SMOOTHING_ON, result.smoothing);
    }

    @Test
    public void 风光算例逐位复现() {
        FocusBracketCalculator.Result result =
                FocusBracketCalculator.compute(24, 11, 1000, 8000, 40);

        assertEquals(1585, result.dofMm, 5.0);
        assertEquals(8, result.count);
        // 换算出的增量远超机身上限 10，必须夹到 10。
        assertEquals(10, result.incrementPrimary);
        assertTrue(result.report().contains("超过机身上限"));
    }

    @Test
    public void 张数用四舍五入而非向上取整() {
        // 风光算例的 (远−近)/步长 = 7.36：向上取整会得到 9 张，与算例要求的 8 张不符。
        assertEquals(8, FocusBracketCalculator.compute(24, 11, 1000, 8000, 40).count);
    }

    @Test
    public void 达到超焦距时单张即可覆盖到无穷远() {
        // 24mm f/11 的超焦距约 1769mm；把近端放到 2000mm 就已经在超焦距之外。
        FocusBracketCalculator.Result result =
                FocusBracketCalculator.compute(24, 11, 2000, 20000, 40);

        assertTrue(result.dofInfinite);
        assertEquals(FocusBracketPlan.MIN_COUNT, result.count);
        assertTrue(result.report().contains("无穷远"));
    }

    @Test
    public void 步长极小时增量夹到下限() {
        // 200mm f/2.8 在 0.4m 处单张景深只有零点几毫米，换算出的增量远小于 1。
        FocusBracketCalculator.Result result =
                FocusBracketCalculator.compute(200, 2.8, 400, 460, 40);

        assertEquals(FocusBracketPlan.MIN_INCREMENT, result.incrementPrimary);
        assertTrue(result.report().contains("下限"));
    }

    @Test
    public void 张数超过上限时夹到上限() {
        FocusBracketCalculator.Result result =
                FocusBracketCalculator.compute(200, 2.8, 400, 6000, 40);

        assertEquals(FocusBracketPlan.MAX_COUNT, result.count);
        assertTrue(result.report().contains("已夹到机身允许的"));
    }

    @Test
    public void 对照模型恒等于五_是已知的退化行为() {
        // 「按景深比例」的系数 = (1 − 重叠率) / 0.12，与焦段、光圈、距离全无关。
        // 这条测试把这个退化行为固定下来，免得以后误以为它是独立信息。
        assertEquals(5, FocusBracketCalculator.compute(50, 8, 500, 1500, 40).incrementDepthBased);
        assertEquals(5, FocusBracketCalculator.compute(24, 11, 1000, 8000, 40).incrementDepthBased);
        assertEquals(5, FocusBracketCalculator.compute(100, 5.6, 300, 3000, 40).incrementDepthBased);
    }

    @Test
    public void 报告必须标注增量换算未实测证实() {
        String report = FocusBracketCalculator.compute(50, 8, 500, 1500, 40).report();
        assertTrue(report.contains("未在机身上实测证实"));
        assertTrue(report.contains("推荐张数：40"));
    }

    @Test
    public void 参数非法时拒绝计算() {
        assertThrows(IllegalArgumentException.class,
                () -> FocusBracketCalculator.compute(0, 8, 500, 1500, 40));
        assertThrows(IllegalArgumentException.class,
                () -> FocusBracketCalculator.compute(50, 0, 500, 1500, 40));
        assertThrows(IllegalArgumentException.class,
                () -> FocusBracketCalculator.compute(50, 8, 1500, 500, 40));
        assertThrows(IllegalArgumentException.class,
                () -> FocusBracketCalculator.compute(50, 8, 500, 500, 40));
        assertThrows(IllegalArgumentException.class,
                () -> FocusBracketCalculator.compute(50, 8, 500, 1500, 100));
        assertThrows(IllegalArgumentException.class,
                () -> FocusBracketCalculator.compute(5000, 8, 500, 1500, 40));
    }

    @Test
    public void 四个场景预设的默认值() {
        assertEquals(200, FocusBracketCalculator.Scene.MACRO.defaultNearMm(), 0.001);
        assertEquals(400, FocusBracketCalculator.Scene.MACRO.defaultFarMm(), 0.001);
        assertEquals(500, FocusBracketCalculator.Scene.PRODUCT.defaultNearMm(), 0.001);
        assertEquals(1500, FocusBracketCalculator.Scene.PRODUCT.defaultFarMm(), 0.001);
        assertEquals(1000, FocusBracketCalculator.Scene.LANDSCAPE.defaultNearMm(), 0.001);
        assertEquals(8000, FocusBracketCalculator.Scene.LANDSCAPE.defaultFarMm(), 0.001);
        assertEquals(2000, FocusBracketCalculator.Scene.ARCHITECTURE.defaultNearMm(), 0.001);
        assertEquals(20000, FocusBracketCalculator.Scene.ARCHITECTURE.defaultFarMm(), 0.001);
        for (FocusBracketCalculator.Scene scene : FocusBracketCalculator.Scene.values()) {
            assertEquals(FocusBracketCalculator.DEFAULT_OVERLAP_PERCENT,
                    scene.defaultOverlapPercent());
        }
    }

    @Test
    public void 按场景预设计算等价于按该场景默认值计算() {
        FocusBracketCalculator.Result byScene =
                FocusBracketCalculator.compute(FocusBracketCalculator.Scene.PRODUCT, 50, 8);
        FocusBracketCalculator.Result byValue =
                FocusBracketCalculator.compute(50, 8, 500, 1500, 40);

        assertEquals(byValue.count, byScene.count);
        assertEquals(byValue.incrementPrimary, byScene.incrementPrimary);
        assertEquals(byValue.dofMm, byScene.dofMm, 0.0001);
    }

    @Test
    public void 摘要含三项关键结果() {
        String summary = FocusBracketCalculator.compute(50, 8, 500, 1500, 40).summary();
        assertTrue(summary.contains("张数 40"));
        assertTrue(summary.contains("增量 5"));
        assertTrue(summary.contains("开启"));
    }
}