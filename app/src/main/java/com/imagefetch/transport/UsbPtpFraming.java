package com.imagefetch.transport;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * USB PTP（ISO 15740 / PIMA 15740）容器编解码。
 *
 * <p>USB 走 bulk 端点时使用原生 PTP 容器，头为 12 字节：
 * {@code [u32 length][u16 type][u16 code][u32 transaction]}，全部小端；
 * 这与 PTP/IP 的 8 字节头（{@code [u32 length][u32 type]}）不同，
 * 两者不能混用同一个解析器。
 */
final class UsbPtpFraming {
    static final int TYPE_COMMAND = 1;
    static final int TYPE_DATA = 2;
    static final int TYPE_RESPONSE = 3;
    static final int HEADER_BYTES = 12;
    /** 单容器上限：与 ZENCHE 口径一致，防止畸形长度导致的内存放大。 */
    static final long MAX_CONTAINER_BYTES = 256L * 1024 * 1024;

    static final class Header {
        final int totalLength;
        final int type;
        final int code;
        final int transaction;

        Header(int totalLength, int type, int code, int transaction) {
            this.totalLength = totalLength;
            this.type = type;
            this.code = code;
            this.transaction = transaction;
        }

        int payloadLength() {
            return totalLength - HEADER_BYTES;
        }
    }

    /** 解析 12 字节容器头；长度非法时抛出，不返回半可信结果。 */
    static Header parseHeader(byte[] header) {
        if (header == null || header.length < HEADER_BYTES) {
            throw new IllegalArgumentException("USB PTP 容器头不足 12 字节");
        }
        ByteBuffer buffer = ByteBuffer.wrap(header, 0, HEADER_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        int totalLength = buffer.getInt();
        int type = buffer.getShort() & 0xffff;
        int code = buffer.getShort() & 0xffff;
        int transaction = buffer.getInt();
        if (totalLength < HEADER_BYTES || totalLength > MAX_CONTAINER_BYTES) {
            throw new IllegalArgumentException("USB PTP 容器长度无效：" + totalLength);
        }
        return new Header(totalLength, type, code, transaction);
    }

    /** 组装完整容器：12 字节头 + 载荷。 */
    static byte[] packet(int type, int code, int transaction, byte[] payload) {
        byte[] body = payload == null ? new byte[0] : payload;
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_BYTES + body.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(HEADER_BYTES + body.length);
        buffer.putShort((short) type);
        buffer.putShort((short) code);
        buffer.putInt(transaction);
        buffer.put(body);
        return buffer.array();
    }

    /** PTP 操作参数编码：每个参数 4 字节小端。 */
    static byte[] parameterBytes(long[] parameters) {
        if (parameters == null || parameters.length == 0) return new byte[0];
        ByteBuffer buffer = ByteBuffer.allocate(parameters.length * 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (long parameter : parameters) buffer.putInt((int) parameter);
        return buffer.array();
    }

    private UsbPtpFraming() {}
}
