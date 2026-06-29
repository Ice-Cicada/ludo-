package network;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Random;

/**
 * 网络工具类 —— 局域网 IP 检测、公网 IP 检测（STUN / HTTP 回退）。
 *
 * <p>全部为静态方法，无需实例化。</p>
 */
public class NetworkUtil {

    private NetworkUtil() {}

    // ---- 局域网 IP ----

    /**
     * 获取本机所有非回环、已启用的局域网 IPv4 地址。
     */
    public static List<String> getAllLanIPs() {
        List<String> ips = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                if (!iface.isUp() || iface.isLoopback() || iface.isVirtual()) continue;
                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && addr.isSiteLocalAddress()) {
                        ips.add(addr.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        if (ips.isEmpty()) {
            ips.add("127.0.0.1");
        }
        return ips;
    }

    /**
     * 获取最优先的局域网 IPv4 地址（优先非虚拟网卡）。
     */
    public static String getBestLanIP() {
        List<String> ips = getAllLanIPs();
        // 优先返回 192.168.x.x
        for (String ip : ips) {
            if (ip.startsWith("192.168.")) return ip;
        }
        // 其次 10.x.x.x
        for (String ip : ips) {
            if (ip.startsWith("10.")) return ip;
        }
        // 其次 172.16-31.x.x
        for (String ip : ips) {
            if (ip.startsWith("172.")) {
                try {
                    int second = Integer.parseInt(ip.split("\\.")[1]);
                    if (second >= 16 && second <= 31) return ip;
                } catch (Exception ignored) {}
            }
        }
        return ips.get(0);
    }

    // ---- 公网 IP：STUN ----

    /**
     * 通过 STUN 协议获取公网 IP。
     *
     * @param stunServer STUN 服务器地址（如 stun.l.google.com）
     * @param stunPort   STUN 端口（通常 19302）
     * @param timeoutMs  超时毫秒数
     * @return 公网 IP 字符串，失败返回 null
     */
    public static String getPublicIPviaSTUN(String stunServer, int stunPort, int timeoutMs) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setSoTimeout(timeoutMs);

            // STUN Binding Request (RFC 5389)
            // Message Type: 0x0001 (Binding Request)
            // Message Length: 0 (no attributes)
            // Magic Cookie: 0x2112A442
            // Transaction ID: 12 random bytes
            byte[] request = new byte[20];
            request[0] = 0x00; // type
            request[1] = 0x01;
            request[2] = 0x00; // length
            request[3] = 0x00;
            // Magic cookie
            request[4] = 0x21;
            request[5] = 0x12;
            request[6] = (byte) 0xA4;
            request[7] = 0x42;
            // Random transaction ID
            byte[] tid = new byte[12];
            new Random().nextBytes(tid);
            System.arraycopy(tid, 0, request, 8, 12);

            InetAddress stunAddr = InetAddress.getByName(stunServer);
            DatagramPacket reqPacket = new DatagramPacket(request, request.length, stunAddr, stunPort);
            socket.send(reqPacket);

            // Receive response
            byte[] buf = new byte[1024];
            DatagramPacket respPacket = new DatagramPacket(buf, buf.length);
            socket.receive(respPacket);

            // Validate magic cookie
            if (buf[4] != 0x21 || buf[5] != 0x12 || buf[6] != (byte) 0xA4 || buf[7] != 0x42) {
                return null;
            }
            // Validate transaction ID matches
            for (int i = 0; i < 12; i++) {
                if (buf[8 + i] != tid[i]) return null;
            }

            // Parse attributes to find XOR-MAPPED-ADDRESS (0x0020)
            int pos = 20;
            while (pos + 4 <= respPacket.getLength()) {
                int attrType = ((buf[pos] & 0xFF) << 8) | (buf[pos + 1] & 0xFF);
                int attrLen = ((buf[pos + 2] & 0xFF) << 8) | (buf[pos + 3] & 0xFF);
                if (attrType == 0x0020 && attrLen >= 8) {
                    // XOR-MAPPED-ADDRESS: family (2 bytes) + port-XOR (2 bytes) + ip-XOR (4 bytes)
                    int ipXor1 = buf[pos + 8] & 0xFF;
                    int ipXor2 = buf[pos + 9] & 0xFF;
                    int ipXor3 = buf[pos + 10] & 0xFF;
                    int ipXor4 = buf[pos + 11] & 0xFF;
                    // XOR with magic cookie
                    int ip1 = ipXor1 ^ 0x21;
                    int ip2 = ipXor2 ^ 0x12;
                    int ip3 = ipXor3 ^ 0xA4;
                    int ip4 = ipXor4 ^ 0x42;
                    return ip1 + "." + ip2 + "." + ip3 + "." + ip4;
                }
                // Also check MAPPED-ADDRESS (0x0001) as fallback
                if (attrType == 0x0001 && attrLen >= 8) {
                    int ip1 = buf[pos + 8] & 0xFF;
                    int ip2 = buf[pos + 9] & 0xFF;
                    int ip3 = buf[pos + 10] & 0xFF;
                    int ip4 = buf[pos + 11] & 0xFF;
                    return ip1 + "." + ip2 + "." + ip3 + "." + ip4;
                }
                pos += 4 + attrLen;
                // Align to 4-byte boundary
                if (attrLen % 4 != 0) pos += 4 - (attrLen % 4);
            }
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            if (socket != null) socket.close();
        }
    }

    // ---- 公网 IP：HTTP 回退 ----

    /**
     * 通过 HTTP 请求获取公网 IP（向 checkip.amazonaws.com 等服务的 GET 请求）。
     *
     * @param serviceHost 服务主机名
     * @param timeoutMs   超时毫秒数
     * @return 公网 IP 字符串，失败返回 null
     */
    public static String getPublicIPviaHTTP(String serviceHost, int timeoutMs) {
        Socket socket = null;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(serviceHost, 80), timeoutMs);
            socket.setSoTimeout(timeoutMs);

            String req = "GET / HTTP/1.0\r\nHost: " + serviceHost + "\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(req.getBytes("UTF-8"));
            socket.getOutputStream().flush();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), "UTF-8"));
            // 跳过 HTTP 头
            String line;
            boolean bodyStarted = false;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) { bodyStarted = true; continue; }
                if (bodyStarted) {
                    String ip = line.trim();
                    if (ip.matches("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")) {
                        return ip;
                    }
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        }
    }
}
