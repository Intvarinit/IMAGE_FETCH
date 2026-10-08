package com.imagefetch.ptp;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * {@link VendorProperty} 的契约测试：属性码取值、标签拼装与探测目标清单。
 * 码值一旦写错，后续所有阶段的命令都会打到别的属性上，所以这里逐个钉住。
 */
public class VendorPropertyTest {
    @Test
    public void 属性码取自libgphoto2的ptp点h() {
        assertEquals(0xd11d, VendorProperty.BRACKET_MODE);
        assertEquals(0xd134, VendorProperty.INTERVAL_SHOOT_SETTING);
        assertEquals(0xd135, VendorProperty.INTERVAL_SHOOT_STATE);
        assertEquals(0xd13d, VendorProperty.HDR_SETTING);
        assertEquals("对焦包围是差分实测找到的，码值必须与 ptp.h:2307 一致",
                0xd208, VendorProperty.FOCUS_SHIFT_SETTING);
        assertEquals(0xd108, VendorProperty.FOCUS_MODE);
        assertEquals(0xd1ba, VendorProperty.AF_METHOD);
        assertEquals(0xd1b0, VendorProperty.EVF_OUTPUT_DEVICE);
        assertEquals(0xd1b1, VendorProperty.EVF_MODE);
    }

    @Test
    public void 探测目标是本阶段的目标属性() {
        assertArrayEquals("顺序即界面展示顺序",
                new int[]{
                        VendorProperty.BRACKET_MODE,
                        VendorProperty.INTERVAL_SHOOT_SETTING,
                        VendorProperty.INTERVAL_SHOOT_STATE,
                        VendorProperty.HDR_SETTING,
                        VendorProperty.FOCUS_SHIFT_SETTING},
                VendorProperty.PROBE_TARGETS);
    }

    @Test
    public void 标签同时给出码与上游符号名() {
        assertEquals("0xD11D EOS_BracketMode（包围模式）",
                VendorProperty.label(VendorProperty.BRACKET_MODE));
        assertEquals("0xD134 EOS_IntervalShootSetting（间隔定时拍摄设置）",
                VendorProperty.label(VendorProperty.INTERVAL_SHOOT_SETTING));
        assertEquals("0xD13D EOS_HDRSetting（HDR 拍摄设置）",
                VendorProperty.label(VendorProperty.HDR_SETTING));
    }

    @Test
    public void 十六进制按四位大写补零() {
        assertEquals("0xD134", VendorProperty.hex(VendorProperty.INTERVAL_SHOOT_SETTING));
        assertEquals("0x0008", VendorProperty.hex(8));
    }

    @Test
    public void 未登记的属性码不编名字() {
        assertEquals(null, VendorProperty.name(0xd999));
        assertEquals("", VendorProperty.symbol(0xd999));
        assertEquals("0xD999", VendorProperty.label(0xd999));
    }
}
