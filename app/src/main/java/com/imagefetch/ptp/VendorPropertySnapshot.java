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

    /** {@link #toText()}/{@link #fromText(String)} 里时间戳行的前缀。 */
    private static final String TEXT_TIME_PREFIX = "takenAt=";

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

    /**
     * 把快照序列化成一行一条的纯文本，供 {@code SharedPreferences} 持久化。
     * 格式：首行 {@code takenAt=<毫秒>}，其后每行 {@code 属性码|解析值|原始字节十六进制}
     * （解析值或原始字节缺失时留空）。原始字节是恢复的唯一依据，必须完整保留。
     */
    public String toText() {
        StringBuilder text = new StringBuilder();
        text.append(TEXT_TIME_PREFIX).append(takenAt).append('\n');
        for (Item item : items) {
            text.append(item.propertyCode).append('|')
                    .append(item.value == UNSET ? "" : Long.toString(item.value)).append('|')
                    .append(hexOf(item.raw)).append('\n');
        }
        return text.toString();
    }

    /**
     * 反序列化 {@link #toText()} 的输出。容错优先：空白行、字段不足、数字/十六进制非法的行
     * 一律跳过；一条有效条目都没有时返回 {@code null}（调用方据此判断「没有基线」）。
     * 时间戳缺失时用 0。
     */
    public static VendorPropertySnapshot fromText(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        long takenAt = 0;
        Map<Integer, Item> items = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.startsWith(TEXT_TIME_PREFIX)) {
                takenAt = parseLong(trimmed.substring(TEXT_TIME_PREFIX.length()), 0);
                continue;
            }
            String[] parts = trimmed.split("\\|", -1);
            if (parts.length < 3) continue;
            long code = parseLong(parts[0], -1);
            if (code < 0 || code > 0xffff) continue;
            long value = parts[1].isEmpty() ? UNSET : parseLong(parts[1], UNSET);
            byte[] raw = parseHex(parts[2]);
            int propertyCode = (int) code;
            items.put(propertyCode, new Item(propertyCode, value, raw));
        }
        if (items.isEmpty()) return null;
        return new VendorPropertySnapshot(takenAt, items);
    }

    private static long parseLong(String text, long fallback) {
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException malformed) {
            return fallback;
        }
    }

    private static String hexOf(byte[] raw) {
        if (raw == null || raw.length == 0) return "";
        StringBuilder text = new StringBuilder(raw.length * 2);
        for (byte value : raw) {
            text.append(Character.forDigit((value >> 4) & 0xf, 16))
                    .append(Character.forDigit(value & 0xf, 16));
        }
        return text.toString();
    }

    private static byte[] parseHex(String text) {
        if (text == null) return null;
        String trimmed = text.trim();
        if (trimmed.isEmpty() || (trimmed.length() % 2) != 0) return null;
        byte[] raw = new byte[trimmed.length() / 2];
        for (int i = 0; i < raw.length; i++) {
            int high = Character.digit(trimmed.charAt(2 * i), 16);
            int low = Character.digit(trimmed.charAt(2 * i + 1), 16);
            if (high < 0 || low < 0) return null;
            raw[i] = (byte) ((high << 4) | low);
        }
        return raw;
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