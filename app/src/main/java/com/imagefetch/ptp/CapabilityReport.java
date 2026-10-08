package com.imagefetch.ptp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * P0「能力探测」的结果：对若干个 Canon 厂商属性给出四列事实——是否存在、当前值原始字节、
 * 允许值列表（{@code 0xC18A} 枚举）、写入是否被接受。
 *
 * <p>本类**纯数据 + 纯格式化**，不碰相机、不依赖 Android，便于 JVM 单测；填充由
 * {@code PtpIpTransport#probeVendorCapabilities} 完成。
 *
 * <p>刻意不做任何结构解读：{@code 0xD134}/{@code 0xD13D} 的值在 EOS RP 上是 24 字节的
 * 不透明区域（libgphoto2 也只有名字、没有结构），P0 的职责是把事实钉下来，字段语义留待
 * 真机差分法确认后再由上层使用。
 */
public final class CapabilityReport {
    /** 标准 PTP 成功响应码。 */
    public static final int RESPONSE_OK = 0x2001;
    /** {@link Entry#writeCode} 的「本次没做写入验证」哨兵。 */
    public static final int WRITE_NOT_ATTEMPTED = 0;

    /** 单个属性的探测结果。 */
    public static final class Entry {
        /** 属性码（见 {@link VendorProperty}）。 */
        public final int propertyCode;
        /** 原始值区域（相机 {@code 0xC189} 推来的字节，原样保存）；{@code null} 表示没读到。 */
        public final byte[] currentValue;
        /** 没读到时的原因；读到了则为 {@code null}。 */
        public final String readFailure;
        /** {@code 0xC18A} 给出的允许值（保持相机顺序）；{@code null} 表示没有枚举可用。 */
        public final int[] allowedValues;
        /** 「原值回写」验证的响应码；{@link #WRITE_NOT_ATTEMPTED} 表示没做。 */
        public final int writeCode;
        /** 写入验证失败的原因（发送异常等）；否则 {@code null}。 */
        public final String writeFailure;
        /** 写入验证后重新读到的值；没做或读不到为 {@code null}。 */
        public final byte[] readBack;
        /** 回读没成功时的原因；否则 {@code null}。 */
        public final String readBackFailure;

        public Entry(
                int propertyCode,
                byte[] currentValue,
                String readFailure,
                int[] allowedValues,
                int writeCode,
                String writeFailure,
                byte[] readBack,
                String readBackFailure) {
            this.propertyCode = propertyCode;
            this.currentValue = currentValue == null ? null : currentValue.clone();
            this.readFailure = readFailure;
            this.allowedValues = allowedValues == null ? null : allowedValues.clone();
            this.writeCode = writeCode;
            this.writeFailure = writeFailure;
            this.readBack = readBack == null ? null : readBack.clone();
            this.readBackFailure = readBackFailure;
        }

        /** 相机是否上报了这个属性（读到了值即视为存在）。 */
        public boolean exists() {
            return currentValue != null && currentValue.length > 0;
        }

        /** 是否有可用的允许值列表。 */
        public boolean hasAllowedValues() {
            return allowedValues != null && allowedValues.length > 0;
        }

        /** 本次是否做过写入验证。 */
        public boolean writeAttempted() {
            return writeCode != WRITE_NOT_ATTEMPTED;
        }

        /** 原值回写是否被相机接受。 */
        public boolean writable() {
            return writeCode == RESPONSE_OK;
        }

        /** 写入验证后回读的值与写入前是否一致（一致才说明「真的写进去了」）。 */
        public boolean writeVerified() {
            return writable() && readBack != null && Arrays.equals(readBack, currentValue);
        }

        /**
         * 值区域是否疑似自带内层长度前缀（首 4 字节小端值恰好等于区域长度）。EOS RP 的
         * {@code 0xD134}/{@code 0xD13D} 都是这种形态（首 4 字节 {@code 0x18} = 24）；返回前缀
         * 长度，或 0 表示没有这种迹象。这只是**事实观察**，不当作结构结论使用。
         */
        public int nestedPrefixLength() {
            if (currentValue == null || currentValue.length <= 4) return 0;
            long first = (currentValue[0] & 0xffL)
                    | ((currentValue[1] & 0xffL) << 8)
                    | ((currentValue[2] & 0xffL) << 16)
                    | ((currentValue[3] & 0xffL) << 24);
            return first == currentValue.length ? 4 : 0;
        }
    }

    private final String cameraName;
    private final List<Entry> entries;
    private final boolean writeVerificationRequested;

    public CapabilityReport(
            String cameraName, List<Entry> entries, boolean writeVerificationRequested) {
        this.cameraName = cameraName == null ? "" : cameraName;
        this.entries = new ArrayList<>(entries);
        this.writeVerificationRequested = writeVerificationRequested;
    }

    public String cameraName() {
        return cameraName;
    }

    public List<Entry> entries() {
        return new ArrayList<>(entries);
    }

    public boolean writeVerificationRequested() {
        return writeVerificationRequested;
    }

    public int readableCount() {
        int count = 0;
        for (Entry entry : entries) {
            if (entry.exists()) count++;
        }
        return count;
    }

    public int writableCount() {
        int count = 0;
        for (Entry entry : entries) {
            if (entry.writable()) count++;
        }
        return count;
    }

    /** 供界面/诊断使用的人类可读报告（中文，多行）。 */
    public String describe() {
        StringBuilder text = new StringBuilder();
        text.append("P0 能力探测 · ").append(cameraName.isEmpty() ? "相机未命名" : cameraName);
        text.append('\n').append("可读 ").append(readableCount()).append('/').append(entries.size());
        text.append(" · 允许值 ")
                .append(countWithAllowedValues()).append('/').append(entries.size());
        text.append(" · 写入验证 ").append(writeVerificationRequested ? "已做" : "未做");
        for (Entry entry : entries) {
            text.append("\n\n").append(VendorProperty.label(entry.propertyCode));
            if (!entry.exists()) {
                text.append("\n  存在：否（").append(entry.readFailure == null
                        ? "相机未推送该属性的当前值" : entry.readFailure).append("）");
                continue;
            }
            text.append("\n  存在：是");
            String raw = hexOf(entry.currentValue);
            text.append("\n  当前值（").append(entry.currentValue.length).append(" 字节）：").append(raw);
            if (entry.nestedPrefixLength() > 0) {
                text.append("\n  观察：首 4 字节 ").append(hexOf(entry.currentValue, 4))
                        .append(" == 区域长度，疑似内层长度前缀（未作结构结论）");
            }
            text.append("\n  允许值：").append(entry.hasAllowedValues()
                    ? valuesOf(entry.allowedValues)
                    : "无（相机未给 0xC18A 枚举）");
            text.append("\n  写入验证：").append(writeVerdict(entry));
        }
        return text.toString();
    }

    private int countWithAllowedValues() {
        int count = 0;
        for (Entry entry : entries) {
            if (entry.hasAllowedValues()) count++;
        }
        return count;
    }

    private static String writeVerdict(Entry entry) {
        if (!entry.writeAttempted()) {
            return "未做（原值回写会真的写相机，需显式触发）";
        }
        if (entry.writeFailure != null) return "发送失败：" + entry.writeFailure;
        if (!entry.writable()) {
            return "被拒 " + String.format(Locale.ROOT, "0x%04X", entry.writeCode)
                    + "（该属性当前不可写）";
        }
        if (entry.readBack == null) {
            return "回 0x2001，但回读失败（"
                    + (entry.readBackFailure == null ? "原因未知" : entry.readBackFailure) + "）";
        }
        if (entry.writeVerified()) return "回 0x2001，回读一致";
        return "回 0x2001，但回读不一致：写入前 " + hexOf(entry.currentValue)
                + " / 写入后 " + hexOf(entry.readBack);
    }

    private static String valuesOf(int[] values) {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < values.length; index++) {
            if (index > 0) text.append(", ");
            text.append(values[index]);
        }
        return text.toString();
    }

    /** 整段字节的十六进制（空格分隔，大写）。 */
    public static String hexOf(byte[] data) {
        if (data == null) return "（无）";
        StringBuilder text = new StringBuilder();
        for (byte value : data) {
            if (text.length() > 0) text.append(' ');
            text.append(String.format(Locale.ROOT, "%02X", value & 0xff));
        }
        return text.toString();
    }

    /** 前 {@code count} 个字节的十六进制。 */
    private static String hexOf(byte[] data, int count) {
        return hexOf(Arrays.copyOf(data, Math.min(count, data.length)));
    }
}