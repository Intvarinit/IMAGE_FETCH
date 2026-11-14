package com.imagefetch.transport;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.SocketFactory;

/**
 * 相机地址自动发现：相机 Wi‑Fi 里没有可用 DNS，也不保证固定 IP，
 * 只能按「已知地址 + 本机所在网段」并发探测 PTP/IP 端口。
 *
 * <p>探测只做 TCP 连接再立刻关闭，不发送任何 PTP 报文：未完成 Init_Command 的
 * 连接相机会直接丢弃，不会占用它的会话槽位。命中第一个就停，不会把整段扫完。
 *
 * <p>候选顺序：调用方给的种子地址（如上次成功的 IP）→ 各机型常见直连地址 →
 * 本机 Wi‑Fi 网段的其余主机。蜂窝 / VPN 接口不参与扫描。
 */
public final class CameraAddressFinder {
    public static final int DEFAULT_PTP_PORT = 15740;

    /** 相机直连模式的常见地址：不同机型落在 192.168.x.2 附近，网关侧也可能应答。 */
    private static final String[] CAMERA_DEFAULT_HOSTS = {
            "192.168.1.2", "192.168.0.2", "192.168.2.2",
            "192.168.1.1", "192.168.0.1", "172.20.10.2"
    };

    /** 参与扫描的接口名前缀：只认 Wi‑Fi / 有线，跳过 rmnet / tun 等蜂窝与隧道接口。 */
    private static final String[] WIFI_INTERFACE_PREFIXES = {"wlan", "eth", "ap", "wifi"};

    /** 前多少个候选（种子 + 默认地址）享受三倍超时的优先探测。 */
    private static final int PRIORITY_HOSTS = 8;

    private CameraAddressFinder() {
    }

    /** 一次发现的结果：命中的地址（可能为 null）与实际探测过的候选数。 */
    public static final class Result {
        public final String host;
        public final int probed;

        Result(String host, int probed) {
            this.host = host;
            this.probed = probed;
        }
    }

    public static Result discover(SocketFactory factory, int port) {
        return discover(factory, port, null, 500L, 24);
    }

    /**
     * @param seeds              优先探测的地址（可为空）
     * @param probeTimeoutMillis 整段扫描时单个 TCP 连接超时
     * @param concurrency        并发探测数
     */
    public static Result discover(SocketFactory factory, int port, Collection<String> seeds,
            long probeTimeoutMillis, int concurrency) {
        List<String> candidates = candidates(localIpv4Addresses(), seeds);
        if (candidates.isEmpty()) return new Result(null, 0);
        // 常见地址（网关、各机型默认 IP、上次成功地址）先单独用更长的超时探一遍：
        // 相机 Wi‑Fi 省电时第一个 SYN 可能要几百毫秒，和整段扫描共用短超时会误判。
        int priorityCount = Math.min(PRIORITY_HOSTS, candidates.size());
        String hit = sweep(factory, port, candidates.subList(0, priorityCount),
                probeTimeoutMillis * 3, Math.min(6, priorityCount));
        if (hit == null && candidates.size() > priorityCount) {
            hit = sweep(factory, port, candidates.subList(priorityCount, candidates.size()),
                    probeTimeoutMillis, concurrency);
        }
        return new Result(hit, candidates.size());
    }

    /** 一轮并发探测：命中第一个就返回，不把整段扫完。 */
    private static String sweep(SocketFactory factory, int port, List<String> hosts,
            long probeTimeoutMillis, int concurrency) {
        if (hosts.isEmpty()) return null;
        int threads = Math.max(1, Math.min(concurrency, hosts.size()));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicReference<String> found = new AtomicReference<>();
        try {
            for (final String host : hosts) {
                pool.execute(() -> {
                    if (found.get() != null) return;
                    if (probe(factory, host, port, (int) probeTimeoutMillis)) {
                        found.compareAndSet(null, host);
                    }
                });
            }
            pool.shutdown();
            // 命中即返回；否则等全部探测跑完（最坏 254/24×超时，约 6 秒）。
            pool.awaitTermination(probeTimeoutMillis * 12 + 1000L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
        }
        return found.get();
    }

    /** 候选地址生成（纯函数，便于契约测试）。 */
    static List<String> candidates(List<String> localIps, Collection<String> seeds) {
        Set<String> ordered = new LinkedHashSet<>();
        if (seeds != null) {
            for (String seed : seeds) {
                String normalized = normalize(seed);
                if (normalized != null) ordered.add(normalized);
            }
        }
        Collections.addAll(ordered, CAMERA_DEFAULT_HOSTS);
        for (String local : localIps) {
            String prefix = subnetPrefix(local);
            if (prefix == null) continue;
            for (int host = 1; host <= 254; host++) {
                String candidate = prefix + host;
                if (!candidate.equals(local)) ordered.add(candidate);
            }
        }
        return new ArrayList<>(ordered);
    }

    /** 取本机 Wi‑Fi / 有线接口的 IPv4 地址（跳过回环、链路本地与蜂窝接口）。 */
    static List<String> localIpv4Addresses() {
        List<String> wifi = new ArrayList<>();
        try {
            for (NetworkInterface networkInterface : Collections.list(
                    NetworkInterface.getNetworkInterfaces())) {
                StringBuilder shown = new StringBuilder();
                for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                    if (shown.length() > 0) shown.append(',');
                    shown.append(address.getHostAddress());
                }
                android.util.Log.i("ImageFetch", "iface " + networkInterface.getName()
                        + " up=" + networkInterface.isUp()
                        + " loopback=" + networkInterface.isLoopback()
                        + " addrs=" + shown);
                if (!networkInterface.isUp() || networkInterface.isLoopback()) continue;
                String name = networkInterface.getName();
                boolean wifiLike = false;
                for (String prefix : WIFI_INTERFACE_PREFIXES) {
                    if (name != null && name.startsWith(prefix)) {
                        wifiLike = true;
                        break;
                    }
                }
                if (!wifiLike) continue;
                for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                    if (!(address instanceof Inet4Address)) continue;
                    if (address.isLoopbackAddress() || address.isLinkLocalAddress()) continue;
                    String host = address.getHostAddress();
                    if (subnetPrefix(host) == null) continue;
                    wifi.add(host);
                }
            }
        } catch (Exception error) {
            android.util.Log.w("ImageFetch", "NetworkInterface 枚举失败", error);
            return wifi;
        }
        android.util.Log.i("ImageFetch", "local wifi ipv4=" + wifi);
        // 没有 Wi‑Fi 接口时不再回退蜂窝网段：相机一定在 Wi‑Fi 上，扫 10.x/100.x 只会白费时间。
        return wifi;
    }

    /** 供错误提示使用的本机网络摘要，例如 "192.168.1.23"；读不到时为固定文案。 */
    public static String localNetworkSummary() {
        List<String> addresses = localIpv4Addresses();
        return addresses.isEmpty() ? "未检测到 Wi‑Fi 地址" : String.join(" / ", addresses);
    }

    private static boolean probe(SocketFactory factory, String host, int port, int timeoutMillis) {
        Socket socket = null;
        try {
            socket = factory.createSocket();
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            return true;
        } catch (Exception refused) {
            return false;
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** 只接受形如 a.b.c.d 的地址，返回去掉空白的字符串；非法输入返回 null。 */
    static String normalize(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (subnetPrefix(trimmed) == null) return null;
        return trimmed;
    }

    /** "192.168.1.23" → "192.168.1."；非法 IPv4 返回 null。 */
    private static String subnetPrefix(String ipv4) {
        if (ipv4 == null) return null;
        String[] parts = ipv4.split("\\.");
        if (parts.length != 4) return null;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) return null;
            try {
                int value = Integer.parseInt(part);
                if (value < 0 || value > 255) return null;
            } catch (NumberFormatException bad) {
                return null;
            }
        }
        return parts[0] + "." + parts[1] + "." + parts[2] + ".";
    }
}
