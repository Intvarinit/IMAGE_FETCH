package com.imagefetch.ptp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.imagefetch.ptp.CapabilityReport.Entry;
import com.imagefetch.testutil.Bytes;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@link CapabilityReport} 的契约测试：报告只负责把「事实」摆出来（存在/字节/允许值/写入是否
 * 被接受），不解读结构。这里钉住判定规则与中文渲染的关键句。
 */
public class CapabilityReportTest {
    /** RP 真机 dump 的 0xD134 值区域形态：24 字节，首 4 字节 0x18 == 区域长度。 */
    private static final byte[] RP_REGION = Bytes.concat(
            Bytes.u32(0x18), Bytes.zeros(12), Bytes.u32(0x0a), Bytes.u32(0x0a));

    @Test
    public void 有值即视为存在() {
        CapabilityReport.Entry entry = new Entry(VendorProperty.BRACKET_MODE,
                Bytes.u32(0), null, null, CapabilityReport.WRITE_NOT_ATTEMPTED, null, null, null);

        assertTrue("读到 0 也是「存在」（0 是合法的 currentvalue，见 RP event 95）", entry.exists());
    }

    @Test
    public void 没读到值时记下原因且不算存在() {
        CapabilityReport.Entry entry = new Entry(VendorProperty.HDR_SETTING,
                null, "相机没有推送 0xD13D 的当前值", null,
                CapabilityReport.WRITE_NOT_ATTEMPTED, null, null, null);

        assertFalse(entry.exists());
        assertEquals("相机没有推送 0xD13D 的当前值", entry.readFailure);
        assertFalse("没读到就谈不上可写", entry.writable());
    }

    @Test
    public void 内层长度前缀只是一种观察() {
        CapabilityReport.Entry entry = new Entry(VendorProperty.INTERVAL_SHOOT_SETTING,
                RP_REGION, null, null, CapabilityReport.WRITE_NOT_ATTEMPTED, null, null, null);
        CapabilityReport.Entry flat = new Entry(VendorProperty.INTERVAL_SHOOT_STATE,
                Bytes.u32(0x0118), null, null, CapabilityReport.WRITE_NOT_ATTEMPTED, null, null, null);

        assertEquals("首 4 字节等于区域长度时给出 4", 4, entry.nestedPrefixLength());
        assertEquals("不满足该形态时给出 0", 0, flat.nestedPrefixLength());
    }

    @Test
    public void 写入回OK且回读一致才算验证通过() {
        CapabilityReport.Entry same = new Entry(VendorProperty.BRACKET_MODE,
                Bytes.u32(1), null, null, CapabilityReport.RESPONSE_OK, null,
                Bytes.u32(1), null);
        CapabilityReport.Entry changed = new Entry(VendorProperty.BRACKET_MODE,
                Bytes.u32(1), null, null, CapabilityReport.RESPONSE_OK, null,
                Bytes.u32(2), null);

        assertTrue(same.writable());
        assertTrue(same.writeVerified());
        assertTrue(changed.writable());
        assertFalse("回读不一致说明没真写进去", changed.writeVerified());
    }

    @Test
    public void 未做写入验证时不算可写() {
        CapabilityReport.Entry entry = new Entry(VendorProperty.BRACKET_MODE,
                Bytes.u32(1), null, null, CapabilityReport.WRITE_NOT_ATTEMPTED, null, null, null);

        assertFalse(entry.writeAttempted());
        assertFalse("只读探测不能推出「可写」", entry.writable());
    }

    @Test
    public void 描述里区分存在与不存在并保留原始字节() {
        List<CapabilityReport.Entry> entries = new ArrayList<>();
        entries.add(new Entry(VendorProperty.INTERVAL_SHOOT_SETTING,
                RP_REGION, null, new int[]{0x0a}, CapabilityReport.WRITE_NOT_ATTEMPTED, null,
                null, null));
        entries.add(new Entry(VendorProperty.HDR_SETTING,
                null, "相机没有推送 0xD13D 的当前值", null,
                CapabilityReport.WRITE_NOT_ATTEMPTED, null, null, null));
        CapabilityReport report = new CapabilityReport("Canon EOS RP", entries, false);

        String text = report.describe();

        assertTrue("标题带机型", text.contains("Canon EOS RP"));
        assertTrue("汇总可读数", text.contains("可读 1/2"));
        assertTrue("汇总写入验证状态", text.contains("写入验证 未做"));
        assertTrue("列出属性标签", text.contains("0xD134 EOS_IntervalShootSetting"));
        assertTrue("原样打印 24 字节", text.contains(
                "18 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 0A 00 00 00 0A 00 00 00"));
        assertTrue("观察到内层长度前缀", text.contains("疑似内层长度前缀"));
        assertTrue("允许值按数字列出", text.contains("允许值：10"));
        assertTrue("读不到的属性给出原因", text.contains("相机没有推送 0xD13D 的当前值"));
        assertTrue("未做写入验证时说明原因", text.contains("原值回写会真的写相机"));
        assertEquals("可写数只统计真的回 0x2001 的", 0, report.writableCount());
    }

    @Test
    public void 十六进制输出为空时给占位符() {
        assertEquals("（无）", CapabilityReport.hexOf(null));
    }

    @Test
    public void 允许值列表按相机顺序渲染() {
        CapabilityReport.Entry entry = new Entry(VendorProperty.BRACKET_MODE,
                Bytes.u32(1), null, new int[]{0x0001, 0x0002, 0xffff},
                CapabilityReport.WRITE_NOT_ATTEMPTED, null, null, null);

        assertTrue(entry.hasAllowedValues());
        String text = new CapabilityReport("X", Arrays.asList(entry), false).describe();
        assertTrue(text.contains("允许值：1, 2, 65535"));
    }
}
