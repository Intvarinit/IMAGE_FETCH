package com.imagefetch.ptp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * 曝光取值表与编解码的契约测试。
 *
 * <p>本表是逐行抄自 libgphoto2 {@code camlibs/ptp2/config.c} 的，
 * 抄错一位在真机上只会表现为「某一档位偏了一格」，很难排查——这里把
 * 关键锚点值与换算都钉住。
 */
public class CanonExposureTest {

    @Test
    public void 取值表去重后不含重复文案() {
        for (CanonExposure.Setting setting : CanonExposure.Setting.values()) {
            List<CanonExposure.Entry> entries = CanonExposure.entries(setting);
            assertTrue(setting.label + " 取值表不应为空", entries.size() > 1);
            for (int i = 0; i < entries.size(); i++) {
                assertNotNull(entries.get(i).label);
                assertEquals("同档位文案不应重复出现",
                        i, firstIndexOfLabel(entries, entries.get(i).label));
            }
        }
    }

    @Test
    public void 快门表关键锚点与相邻档编码() {
        assertEquals(0x0000, valueOf(CanonExposure.Setting.SHUTTER_SPEED, "auto"));
        assertEquals(0x0070, valueOf(CanonExposure.Setting.SHUTTER_SPEED, "1/125"));
        assertEquals(0x00a0, valueOf(CanonExposure.Setting.SHUTTER_SPEED, "1/8000"));
        // 每整档相差 8：1/125(0x70) 相邻的 1/250(0x78) 与 1/60(0x68)。
        assertEquals(0x0078, valueOf(CanonExposure.Setting.SHUTTER_SPEED, "1/250"));
        assertEquals(0x0068, valueOf(CanonExposure.Setting.SHUTTER_SPEED, "1/60"));
    }

    @Test
    public void 光圈表关键锚点() {
        // 编码 = 16 * log2(F) + 8。
        assertEquals(0x0008, valueOf(CanonExposure.Setting.APERTURE, "1"));
        assertEquals(0x0020, valueOf(CanonExposure.Setting.APERTURE, "2.8"));
        assertEquals(0x0038, valueOf(CanonExposure.Setting.APERTURE, "8"));
        assertEquals(0x0050, valueOf(CanonExposure.Setting.APERTURE, "22"));
    }

    @Test
    public void Iso表关键锚点且不含恢复出厂档() {
        assertEquals(0x0048, valueOf(CanonExposure.Setting.ISO, "100"));
        assertEquals(0x0058, valueOf(CanonExposure.Setting.ISO, "400"));
        assertEquals(0x0068, valueOf(CanonExposure.Setting.ISO, "1600"));
        assertEquals(0x0088, valueOf(CanonExposure.Setting.ISO, "25600"));
        // 0xffff 是「恢复出厂设置」，不是一档感光度，不能出现在可选列表里。
        assertEquals(-1, CanonExposure.indexOfValue(CanonExposure.Setting.ISO, 0xffff));
    }

    @Test
    public void Iso自动档排在数值档之前() {
        List<CanonExposure.Entry> iso = CanonExposure.entries(CanonExposure.Setting.ISO);
        assertEquals(0x0000, iso.get(0).value);
        assertEquals(0x0001, iso.get(1).value);
        // 之后按 ISO 数值升序：6 → 12 → 25 …
        assertEquals("6", iso.get(2).label);
    }

    @Test
    public void 默认档为常用手持参数() {
        assertEquals("1/125", CanonExposure.entries(CanonExposure.Setting.SHUTTER_SPEED)
                .get(CanonExposure.defaultIndex(CanonExposure.Setting.SHUTTER_SPEED)).label);
        assertEquals("4", CanonExposure.entries(CanonExposure.Setting.APERTURE)
                .get(CanonExposure.defaultIndex(CanonExposure.Setting.APERTURE)).label);
        assertEquals("100", CanonExposure.entries(CanonExposure.Setting.ISO)
                .get(CanonExposure.defaultIndex(CanonExposure.Setting.ISO)).label);
    }

    @Test
    public void 显示文案按参数加前缀() {
        assertEquals("F2.8", CanonExposure.display(CanonExposure.Setting.APERTURE, "2.8"));
        assertEquals("ISO 1600", CanonExposure.display(CanonExposure.Setting.ISO, "1600"));
        assertEquals("1/125", CanonExposure.display(CanonExposure.Setting.SHUTTER_SPEED, "1/125"));
        assertEquals("—", CanonExposure.display(CanonExposure.Setting.ISO, null));
    }

    @Test
    public void 标准属性读数换算回档位() {
        // 0x5007 F×100：F4 → 400
        assertEquals("F4 应对上档位 4",
                CanonExposure.indexOfLabel(CanonExposure.Setting.APERTURE, "4"),
                CanonExposure.matchStandard(CanonExposure.Setting.APERTURE, 400));
        // 0x500f 原始 ISO：1600
        assertEquals(CanonExposure.indexOfLabel(CanonExposure.Setting.ISO, "1600"),
                CanonExposure.matchStandard(CanonExposure.Setting.ISO, 1600));
        // 0x500d 1/10000 秒：1/125 秒 → 80
        assertEquals(CanonExposure.indexOfLabel(CanonExposure.Setting.SHUTTER_SPEED, "1/125"),
                CanonExposure.matchStandard(CanonExposure.Setting.SHUTTER_SPEED, 80));
    }

    @Test
    public void 表内没有的读数按秒数就近匹配而不是报错() {
        // 1/95.2 秒：表里只有 1/90 与 1/100，换算出的文案 "1/95" 命中不了任何一档，
        // 此时必须退化为就近匹配，而不是把「读不到」当成相机不支持。
        int index = CanonExposure.matchStandard(CanonExposure.Setting.SHUTTER_SPEED, 105);
        assertTrue("不能因为读数不在表内就返回 -1", index >= 0);
    }

    @Test
    public void 秒数文案格式与表内风格一致() {
        assertEquals("1/125", CanonExposure.formatSeconds(1 / 125.0));
        assertEquals("2", CanonExposure.formatSeconds(2.0));
        assertEquals("2.5", CanonExposure.formatSeconds(2.5));
        assertEquals("30", CanonExposure.formatSeconds(30.0));
    }

    @Test
    public void 按相机允许值收窄取值表且保持原表顺序() {
        List<CanonExposure.Entry> full = CanonExposure.entries(CanonExposure.Setting.APERTURE);
        // allowed 故意乱序：结果必须仍按原表顺序（F2.8 → F8 → F22）。
        int[] allowed = {0x0050, 0x0020, 0x0038};
        List<CanonExposure.Entry> restricted =
                CanonExposure.restrict(CanonExposure.Setting.APERTURE, allowed);
        assertEquals(3, restricted.size());
        assertEquals("2.8", restricted.get(0).label);
        assertEquals("8", restricted.get(1).label);
        assertEquals("22", restricted.get(2).label);
        assertTrue("收窄只做子集，不应引入全表没有的档", full.containsAll(restricted));
    }

    @Test
    public void 允许值为空或无命中时交回全表() {
        List<CanonExposure.Entry> full = CanonExposure.entries(CanonExposure.Setting.ISO);
        assertEquals("null 允许值应交回全表", full,
                CanonExposure.restrict(CanonExposure.Setting.ISO, null));
        assertEquals("空允许值应交回全表", full,
                CanonExposure.restrict(CanonExposure.Setting.ISO, new int[0]));
        assertEquals("一个都没命中也不能给空列表", full,
                CanonExposure.restrict(CanonExposure.Setting.ISO, new int[]{0xdead}));
    }

    private static int firstIndexOfLabel(List<CanonExposure.Entry> entries, String label) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).label.equals(label)) return i;
        }
        return -1;
    }

    private static int valueOf(CanonExposure.Setting setting, String label) {
        int index = CanonExposure.indexOfLabel(setting, label);
        assertTrue("取值表里应存在 " + setting.label + " = " + label, index >= 0);
        return CanonExposure.entries(setting).get(index).value;
    }
}
