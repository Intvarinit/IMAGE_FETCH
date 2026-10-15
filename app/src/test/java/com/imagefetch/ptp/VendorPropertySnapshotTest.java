package com.imagefetch.ptp;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.imagefetch.testutil.Bytes;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link VendorPropertySnapshot} 的契约测试：差分法是反推 0xD1xx 取值语义的唯一手段，
 * 比对规则写错就会给出「没变」的假结论，所以逐条钉住。
 */
public class VendorPropertySnapshotTest {
    private static final int PROP_INTERVAL = VendorProperty.INTERVAL_SHOOT_SETTING;
    private static final int PROP_HDR = VendorProperty.HDR_SETTING;

    @Test
    public void 条目按属性码升序() {
        Map<Integer, VendorPropertySnapshot.Item> items = new LinkedHashMap<>();
        items.put(PROP_HDR, item(PROP_HDR, Bytes.u32(1)));
        items.put(PROP_INTERVAL, item(PROP_INTERVAL, Bytes.u32(0)));

        VendorPropertySnapshot snapshot = new VendorPropertySnapshot(1L, items);

        assertEquals("顺序必须稳定，否则前后对比时看着像在乱跳",
                PROP_INTERVAL, snapshot.items().get(0).propertyCode);
        assertEquals(PROP_HDR, snapshot.items().get(1).propertyCode);
    }

    @Test
    public void 原始字节变化被判为变化() {
        // 关键场景：24 字节里只有高位字段变了，低 16 位没变——按解析值比会漏。
        byte[] before = Bytes.concat(Bytes.u32(0x18), Bytes.zeros(12), Bytes.u32(0x0a), Bytes.u32(0x0a));
        byte[] after = Bytes.concat(Bytes.u32(0x18), Bytes.zeros(12), Bytes.u32(0x14), Bytes.u32(0x0a));

        List<VendorPropertySnapshot.Diff> diffs =
                snapshotOf(item(PROP_INTERVAL, after)).diff(snapshotOf(item(PROP_INTERVAL, before)));

        assertEquals(1, diffs.size());
        assertEquals(VendorPropertySnapshot.Diff.CHANGED, diffs.get(0).kind);
        assertEquals("必须按字节比，不能只看低 16 位", 0x0aL, Bytes.getU32(before, 16));
        assertEquals(0x14L, Bytes.getU32(after, 16));
    }

    @Test
    public void 字节完全相同时不算变化() {
        byte[] same = Bytes.u32(0x18);

        List<VendorPropertySnapshot.Diff> diffs =
                snapshotOf(item(PROP_HDR, same)).diff(snapshotOf(item(PROP_HDR, Bytes.u32(0x18))));

        assertTrue("没变就是没变，不能报假变化", diffs.isEmpty());
    }

    @Test
    public void 变化长度也算变化() {
        List<VendorPropertySnapshot.Diff> diffs =
                snapshotOf(item(PROP_HDR, Bytes.zeros(24)))
                        .diff(snapshotOf(item(PROP_HDR, Bytes.zeros(4))));

        assertEquals(1, diffs.size());
        assertEquals(VendorPropertySnapshot.Diff.CHANGED, diffs.get(0).kind);
    }

    @Test
    public void 只报原始字节的属性也能参与比对() {
        // 有的属性相机只给了 0xC189 的原始字节、没解析出值：此时 value 为 UNSET，
        // 必须退回按字节比，否则会被当成「值相等」而漏报。
        VendorPropertySnapshot.Item left = new VendorPropertySnapshot.Item(
                PROP_INTERVAL, VendorPropertySnapshot.UNSET, Bytes.u32(1));
        VendorPropertySnapshot.Item right = new VendorPropertySnapshot.Item(
                PROP_INTERVAL, VendorPropertySnapshot.UNSET, Bytes.u32(2));

        assertEquals(1, snapshotOf(right).diff(snapshotOf(left)).size());
        assertFalse(left.hasRaw() && right.raw.length == 0);
    }

    @Test
    public void 新增与消失分别成条() {
        VendorPropertySnapshot baseline = snapshotOf(item(PROP_HDR, Bytes.u32(1)));
        Map<Integer, VendorPropertySnapshot.Item> now = new LinkedHashMap<>();
        now.put(PROP_INTERVAL, item(PROP_INTERVAL, Bytes.u32(2)));

        List<VendorPropertySnapshot.Diff> diffs =
                new VendorPropertySnapshot(2L, now).diff(baseline);

        assertEquals(2, diffs.size());
        assertEquals("新增的属性码更小，排在前面", VendorPropertySnapshot.Diff.ADDED, diffs.get(0).kind);
        assertEquals(PROP_INTERVAL, diffs.get(0).propertyCode);
        assertNull(diffs.get(0).before);
        assertEquals(VendorPropertySnapshot.Diff.REMOVED, diffs.get(1).kind);
        assertEquals(PROP_HDR, diffs.get(1).propertyCode);
        assertNull(diffs.get(1).after);
    }

    @Test
    public void 没有基线时给出操作顺序而不是空结果() {
        String text = snapshotOf(item(PROP_HDR, Bytes.u32(1))).describeDiff(null);

        assertTrue(text.contains("取基线快照"));
        assertTrue(text.contains("对比"));
    }

    @Test
    public void 无变化时明确说没变并给出排查方向() {
        VendorPropertySnapshot snapshot = snapshotOf(item(PROP_HDR, Bytes.u32(1)));

        String text = snapshot.describeDiff(snapshotOf(item(PROP_HDR, Bytes.u32(1))));

        assertTrue("必须说「没检测到任何变化」，否则用户以为工具坏了", text.contains("没有检测到任何变化"));
        assertTrue("并提示事件可能还没到", text.contains("稍等"));
    }

    @Test
    public void 差异清单同时打印前后字节() {
        VendorPropertySnapshot baseline =
                snapshotOf(item(PROP_INTERVAL, Bytes.concat(Bytes.u32(0x18), Bytes.zeros(12),
                        Bytes.u32(0x0a), Bytes.u32(0x0a))));
        VendorPropertySnapshot now =
                snapshotOf(item(PROP_INTERVAL, Bytes.concat(Bytes.u32(0x18), Bytes.zeros(12),
                        Bytes.u32(0x14), Bytes.u32(0x0a))));

        String text = now.describeDiff(baseline);

        assertTrue(text.contains("变化 1 项"));
        assertTrue("用中文标签而不是只给十六进制码", text.contains("EOS_IntervalShootSetting"));
        assertTrue(text.contains("前："));
        assertTrue("改动的那个字段要能看出来", text.contains("0A 00 00 00"));
        assertTrue(text.contains("14 00 00 00"));
    }

    @Test
    public void 值文本优先给原始字节并标注长度() {
        VendorPropertySnapshot.Item item = item(PROP_INTERVAL, Bytes.zeros(24));

        assertEquals("原始字节才是差分法的依据，必须优先展示", true, item.valueText().contains("24 字节"));
        assertTrue(item.valueText().startsWith("00 00"));
    }

    @Test
    public void 全量清单列出属性标签与条目数() {
        String text = snapshotOf(item(PROP_INTERVAL, Bytes.u32(0))).describe();

        assertTrue(text.contains("共 1 个"));
        assertTrue(text.contains(VendorProperty.label(PROP_INTERVAL)));
    }

    @Test
    public void 快照文本往返保真原始字节与时间戳() {
        // 原始字节是恢复的唯一依据，序列化后必须逐字节还原，否则「恢复基线」会写回错值。
        byte[] region = Bytes.concat(Bytes.u32(0x18), Bytes.zeros(12), Bytes.u32(0x0a), Bytes.u32(0x0a));
        VendorPropertySnapshot original = new VendorPropertySnapshot(1234L, itemsOf(item(PROP_INTERVAL, region)));

        VendorPropertySnapshot restored = VendorPropertySnapshot.fromText(original.toText());

        assertEquals("时间戳要能还原", 1234L, restored.takenAt());
        assertEquals(1, restored.size());
        assertArrayEquals(region, restored.find(PROP_INTERVAL).raw);
    }

    @Test
    public void 无原始字节的条目往返后值为解析值且无原始字节() {
        Map<Integer, VendorPropertySnapshot.Item> items = new LinkedHashMap<>();
        items.put(PROP_HDR, new VendorPropertySnapshot.Item(PROP_HDR, 7L, null));

        VendorPropertySnapshot restored =
                VendorPropertySnapshot.fromText(new VendorPropertySnapshot(0L, items).toText());

        assertEquals(7L, restored.find(PROP_HDR).value);
        assertFalse("没有原始字节就不该凭空造出字节数组", restored.find(PROP_HDR).hasRaw());
    }

    @Test
    public void 空文本或垃圾文本都返回没有基线() {
        assertNull(VendorPropertySnapshot.fromText(null));
        assertNull(VendorPropertySnapshot.fromText("   \n\n"));
        assertNull("字段不足的记录一律跳过，一条有效都没有时视作没有基线",
                VendorPropertySnapshot.fromText("这不是一条有效记录"));
    }

    private static VendorPropertySnapshot.Item item(int code, byte[] raw) {
        return new VendorPropertySnapshot.Item(code, VendorPropertySnapshot.UNSET, raw);
    }

    private static Map<Integer, VendorPropertySnapshot.Item> itemsOf(VendorPropertySnapshot.Item item) {
        Map<Integer, VendorPropertySnapshot.Item> items = new LinkedHashMap<>();
        items.put(item.propertyCode, item);
        return items;
    }

    private static VendorPropertySnapshot snapshotOf(VendorPropertySnapshot.Item item) {
        return new VendorPropertySnapshot(1L, itemsOf(item));
    }
}