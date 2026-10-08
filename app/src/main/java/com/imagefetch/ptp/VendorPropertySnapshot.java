package com.imagefetch.ptp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 某一时刻「相机通过 {@code 0xC189 PropValueChanged} 报过的所有厂商属性」的只读快照。
 *
 * <p>它存在的唯一理由：0xD1xx 这类属性的取值语义（哪个字段是间隔秒数、哪个是张数、
 * 对焦包围到底挂在哪个属性码上）在新的机型上无从查证——libgphoto2 对 {@code 0xD134}/
 * {@code 0xD135}/{@code 0xD13D} 一条记录都没有，对 Canon 的「对焦包围」更是完全没有属性。
 * 所以只能用<b>差分法</b>反推：机身改一次设置 → 抓一次快照 → 比对字节差异。
 * 本类不做任何结构解读，只负责「原样摆出来」与「两份快照差在哪」。
 */
public final class VendorPropertySnapshot {
    /** {@code value} 缺失（相机只给了原始字节，或尚未收到该属性的值事件）。 */
    public static final long UNSET = Long.MIN_VALUE;

    /** 清单最多打印多少条，防止把下拉框撑爆。 */
    private static final int MAX_LISTED = 200;

    /** 单个属性在一次快照里的样子。不可变；{@code raw} 为防御性拷贝。 */
    public static final class Item {
        public final int propertyCode;
        /** {@code 0xC189} 里解析出的值；未解析出时为 {@link #UNSET}。 */
        public final long value;
        /** 原始值区域（可能是 24 字节的不透明块）；相机没给值时可能为 null。 */
        public final byte[] raw;

        public Item(int propertyCode, long value, byte[] raw) {
            this.propertyCode = propertyCode;
            this.value = value;
            this.raw = raw == null ? null : raw.clone();
        }

        public boolean hasRaw() {
            return raw != null && raw.length > 0;
        }

        /** 原始字节的十六进制；没有原始字节时退化成解析值。 */
        public String valueText() {
            if (hasRaw()) return CapabilityReport.hexOf(raw) + "  (" + raw.length + " 字节)";
            if (value != UNSET) return "值 " + value;
            return "（无）";
        }
    }

    /** 两份快照之间的一条差异。 */
    public static final class Diff {
        public static final int ADDED = 1;
        public static final int REMOVED = 2;
        public static final int CHANGED = 3;

        public final int propertyCode;
        public final int kind;
        /** 基线里的样子；{@code ADDED} 时为 null。 */
        public final Item before;
        /** 当前的样子；{@code REMOVED} 时为 null。 */
        public final Item after;

        Diff(int propertyCode, int kind, Item before, Item after) {
            this.propertyCode = propertyCode;
            this.kind = kind;
            this.before = before;
            this.after = after;
        }
    }

    private final long takenAt;
    private final List<Item> items;

    public VendorPropertySnapshot(long takenAt, Map<Integer, Item> items) {
        this.takenAt = takenAt;
        List<Item> sorted = new ArrayList<>(items.values());
        Collections.sort(sorted, new Comparator<Item>() {
            @Override
            public int compare(Item left, Item right) {
                return Integer.compare(left.propertyCode, right.propertyCode);
            }
        });
        this.items = Collections.unmodifiableList(sorted);
    }

    /** 取快照的时刻（{@code System.currentTimeMillis()}）。 */
    public long takenAt() {
        return takenAt;
    }

    /** 按属性码升序的全部条目。 */
    public List<Item> items() {
        return items;
    }

    public int size() {
        return items.size();
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public Item find(int propertyCode) {
        for (Item item : items) {
            if (item.propertyCode == propertyCode) return item;
        }
        return null;
    }

    /**
     * 相对基线的差异。判据是<b>原始字节</b>（有原始字节时），否则退回解析值——
     * 24 字节的不透明块只能按字节比，按解析出的值比会漏掉「高位字段变了但低 16 位没变」。
     */
    public List<Diff> diff(VendorPropertySnapshot baseline) {
        Map<Integer, Item> before = new LinkedHashMap<>();
        if (baseline != null) {
            for (Item item : baseline.items) before.put(item.propertyCode, item);
        }
        Map<Integer, Item> after = new LinkedHashMap<>();
        for (Item item : items) after.put(item.propertyCode, item);

        List<Diff> result = new ArrayList<>();
        for (Integer code : unionCodes(before, after)) {
            Item oldItem = before.get(code);
            Item newItem = after.get(code);
            if (oldItem == null) {
                result.add(new Diff(code, Diff.ADDED, null, newItem));
            } else if (newItem == null) {
                result.add(new Diff(code, Diff.REMOVED, oldItem, null));
            } else if (differs(oldItem, newItem)) {
                result.add(new Diff(code, Diff.CHANGED, oldItem, newItem));
            }
        }
        return result;
    }

    /** 某属性在两份快照里是否不一样。 */
    private static boolean differs(Item left, Item right) {
        if (left.hasRaw() && right.hasRaw()) {
            if (left.raw.length != right.raw.length) return true;
            for (int i = 0; i < left.raw.length; i++) {
                if (left.raw[i] != right.raw[i]) return true;
            }
            return false;
        }
        return left.value != right.value;
    }

    /** 两份快照涉及的全部属性码，升序去重。 */
    private static List<Integer> unionCodes(Map<Integer, Item> left, Map<Integer, Item> right) {
        List<Integer> codes = new ArrayList<>(left.keySet());
        for (Integer code : right.keySet()) {
            if (!left.containsKey(code)) codes.add(code);
        }
        Collections.sort(codes);
        return codes;
    }

    /** 全量清单（按属性码升序），供「先看相机到底报了什么」用。 */
    public String describe() {
        StringBuilder text = new StringBuilder();
        text.append("相机上报的厂商属性共 ").append(items.size()).append(" 个")
                .append("（取自 0xC189 事件缓存，只读）\n");
        int shown = 0;
        for (Item item : items) {
            if (shown >= MAX_LISTED) {
                text.append("… 还有 ").append(items.size() - MAX_LISTED).append(" 个未显示\n");
                break;
            }
            text.append(VendorProperty.label(item.propertyCode)).append('\n')
                    .append("    ").append(item.valueText()).append('\n');
            shown++;
        }
        return text.toString();
    }

    /** 相对基线的差异清单。没有差异时明确说「没变」——否则用户会以为工具坏了。 */
    public String describeDiff(VendorPropertySnapshot baseline) {
        if (baseline == null) {
            return "还没有基线快照。\n\n顺序：先点「取基线快照」→ 去机身上改一项设置 → 回来点「对比」。";
        }
        List<Diff> diffs = diff(baseline);
        StringBuilder text = new StringBuilder();
        text.append("基线 ").append(baseline.size()).append(" 个属性 → 现在 ")
                .append(items.size()).append(" 个；变化 ").append(diffs.size()).append(" 项\n");
        if (diffs.isEmpty()) {
            text.append("\n没有检测到任何变化。可能原因：\n")
                    .append("· 机身改的那项设置不走 PTP 上报\n")
                    .append("· 事件还没到（稍等 1–2 秒再点一次「对比」）\n")
                    .append("· 相机不在遥控/事件态（重连一次相机）\n");
            return text.toString();
        }
        for (Diff diff : diffs) {
            text.append('\n').append(kindLabel(diff.kind)).append(' ')
                    .append(VendorProperty.label(diff.propertyCode)).append('\n');
            if (diff.before != null) text.append("    前：").append(diff.before.valueText()).append('\n');
            if (diff.after != null) text.append("    后：").append(diff.after.valueText()).append('\n');
        }
        return text.toString();
    }

    private static String kindLabel(int kind) {
        switch (kind) {
            case Diff.ADDED:
                return "【新增】";
            case Diff.REMOVED:
                return "【消失】";
            default:
                return "【变化】";
        }
    }
}