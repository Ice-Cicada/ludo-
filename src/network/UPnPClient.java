package network;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * UPnP IGD 客户端 —— 通过 SSDP 发现网关，通过 SOAP 进行端口映射。
 *
 * <p>实现 {@link AutoCloseable}，可在 try-with-resources 中使用，
 * 关闭时自动删除所有已创建的端口映射。</p>
 *
 * <p>用法：</p>
 * <pre>{@code
 * try (UPnPClient upnp = new UPnPClient(3000)) {
 *     if (upnp.isAvailable()) {
 *         upnp.addPortMapping("192.168.1.5", 9876, 9876, "Ludo", "TCP");
 *         System.out.println("公网 IP: " + upnp.getExternalIP());
 *     }
 * }
 * }</pre>
 */
public class UPnPClient implements AutoCloseable {

    private static final String SSDP_ADDR = "239.255.255.250";
    private static final int SSDP_PORT = 1900;
    private static final String IGD_DEVICE = "urn:schemas-upnp-org:device:InternetGatewayDevice:1";
    private static final String WANIP_SERVICE = "urn:schemas-upnp-org:service:WANIPConnection:1";
    private static final String WANPPP_SERVICE = "urn:schemas-upnp-org:service:WANPPPConnection:1";

    private String controlURL;
    private String serviceType;
    private String baseURL;
    private String externalIP;
    private final List<PortMapping> createdMappings = new ArrayList<>();
    private final int timeoutMs;

    /**
     * 构建并尝试发现 UPnP IGD 设备。
     *
     * @param timeoutMs 网络超时毫秒数
     */
    public UPnPClient(int timeoutMs) {
        this.timeoutMs = timeoutMs;
        discover();
    }

    /** 是否成功发现 IGD 设备 */
    public boolean isAvailable() {
        return controlURL != null && externalIP != null;
    }

    /** 获取从网关获得的公网 IP（仅在 isAvailable() 为 true 时有效） */
    public String getExternalIP() {
        return externalIP;
    }

    // ---- 端口映射 ----

    /**
     * 添加端口映射。
     *
     * @param internalIP   内网主机 IP
     * @param internalPort 内网端口
     * @param externalPort 外网端口
     * @param description  描述（显示在路由器管理界面）
     * @param protocol     协议（TCP 或 UDP）
     * @return 是否成功
     */
    public boolean addPortMapping(String internalIP, int internalPort,
                                   int externalPort, String description, String protocol) {
        if (!isAvailable()) return false;

        String soapBody =
            "<?xml version=\"1.0\"?>\r\n" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\r\n" +
            "<s:Body>\r\n" +
            "<u:AddPortMapping xmlns:u=\"" + serviceType + "\">\r\n" +
            "<NewRemoteHost></NewRemoteHost>\r\n" +
            "<NewExternalPort>" + externalPort + "</NewExternalPort>\r\n" +
            "<NewProtocol>" + protocol + "</NewProtocol>\r\n" +
            "<NewInternalPort>" + internalPort + "</NewInternalPort>\r\n" +
            "<NewInternalClient>" + internalIP + "</NewInternalClient>\r\n" +
            "<NewEnabled>1</NewEnabled>\r\n" +
            "<NewPortMappingDescription>" + escapeXml(description) + "</NewPortMappingDescription>\r\n" +
            "<NewLeaseDuration>0</NewLeaseDuration>\r\n" +
            "</u:AddPortMapping>\r\n" +
            "</s:Body>\r\n" +
            "</s:Envelope>\r\n";

        String action = serviceType + "#AddPortMapping";
        String response = soapRequest(action, soapBody);
        if (response != null && response.contains("AddPortMappingResponse")) {
            createdMappings.add(new PortMapping(externalPort, protocol));
            return true;
        }
        // 如果端口已映射，也视为成功（可能是上次未清理）
        if (response != null && response.contains("ConflictInMappingEntry")) {
            createdMappings.add(new PortMapping(externalPort, protocol));
            return true;
        }
        return false;
    }

    /**
     * 删除端口映射。
     *
     * @param externalPort 外网端口
     * @param protocol     协议（TCP 或 UDP）
     * @return 是否成功
     */
    public boolean deletePortMapping(int externalPort, String protocol) {
        if (!isAvailable()) return false;

        String soapBody =
            "<?xml version=\"1.0\"?>\r\n" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\r\n" +
            "<s:Body>\r\n" +
            "<u:DeletePortMapping xmlns:u=\"" + serviceType + "\">\r\n" +
            "<NewRemoteHost></NewRemoteHost>\r\n" +
            "<NewExternalPort>" + externalPort + "</NewExternalPort>\r\n" +
            "<NewProtocol>" + protocol + "</NewProtocol>\r\n" +
            "</u:DeletePortMapping>\r\n" +
            "</s:Body>\r\n" +
            "</s:Envelope>\r\n";

        String action = serviceType + "#DeletePortMapping";
        String response = soapRequest(action, soapBody);
        createdMappings.removeIf(m -> m.externalPort == externalPort && m.protocol.equals(protocol));
        return response != null && response.contains("DeletePortMappingResponse");
    }

    // ---- AutoCloseable ----

    /** 删除所有本实例创建的端口映射。 */
    @Override
    public void close() {
        for (PortMapping m : new ArrayList<>(createdMappings)) {
            deletePortMapping(m.externalPort, m.protocol);
        }
    }

    // ---- 内部：SSDP 发现 ----

    private void discover() {
        try {
            // 1. SSDP M-SEARCH
            String locationURL = ssdpDiscover();
            if (locationURL == null) return;

            // 2. 获取设备描述 XML
            String descXml = httpGet(locationURL);
            if (descXml == null) return;

            // 3. 解析 XML 找 WANIPConnection 或 WANPPPConnection
            if (!parseDeviceDesc(descXml, locationURL)) return;

            // 4. 获取外网 IP
            externalIP = getExternalIPAddress();
        } catch (Exception e) {
            // UPnP 不可用，静默失败
        }
    }

    /** SSDP M-SEARCH 多播发现，返回 IGD 的 LOCATION URL */
    private String ssdpDiscover() throws Exception {
        MulticastSocket socket = null;
        DatagramSocket recvSocket = null;
        try {
            // 构建 M-SEARCH 请求
            StringBuilder sb = new StringBuilder();
            sb.append("M-SEARCH * HTTP/1.1\r\n");
            sb.append("HOST: ").append(SSDP_ADDR).append(":").append(SSDP_PORT).append("\r\n");
            sb.append("MAN: \"ssdp:discover\"\r\n");
            sb.append("MX: 3\r\n");
            sb.append("ST: ").append(IGD_DEVICE).append("\r\n");
            sb.append("\r\n");
            byte[] data = sb.toString().getBytes(StandardCharsets.UTF_8);

            // 发送多播
            socket = new MulticastSocket();
            socket.setTimeToLive(4);
            DatagramPacket packet = new DatagramPacket(data, data.length,
                    InetAddress.getByName(SSDP_ADDR), SSDP_PORT);
            socket.send(packet);

            // 接收响应（从随机端口接收单播响应）
            recvSocket = new DatagramSocket();
            recvSocket.setSoTimeout(timeoutMs);
            byte[] buf = new byte[4096];
            long deadline = System.currentTimeMillis() + timeoutMs;

            while (System.currentTimeMillis() < deadline) {
                try {
                    DatagramPacket resp = new DatagramPacket(buf, buf.length);
                    recvSocket.receive(resp);
                    String response = new String(resp.getData(), 0, resp.getLength(), StandardCharsets.UTF_8);

                    // 检查是否包含 IGD 设备类型
                    if (response.contains(IGD_DEVICE) || response.contains("InternetGatewayDevice")) {
                        String loc = extractHeader(response, "LOCATION");
                        if (loc != null) return loc;
                        loc = extractHeader(response, "Location");
                        if (loc != null) return loc;
                    }
                } catch (java.net.SocketTimeoutException e) {
                    break;
                }
            }
            return null;
        } finally {
            try { if (socket != null) socket.close(); } catch (Exception ignored) {}
            try { if (recvSocket != null) recvSocket.close(); } catch (Exception ignored) {}
        }
    }

    private static String extractHeader(String http, String headerName) {
        String search = headerName.toUpperCase() + ":";
        // 大小写不敏感搜索
        int idx = http.toUpperCase().indexOf(search);
        if (idx < 0) {
            // 尝试原样搜索
            search = headerName + ":";
            idx = http.indexOf(search);
            if (idx < 0) return null;
        }
        idx += search.length();
        while (idx < http.length() && http.charAt(idx) == ' ') idx++;
        int end = http.indexOf('\r', idx);
        if (end < 0) end = http.indexOf('\n', idx);
        if (end < 0) end = http.length();
        return http.substring(idx, end).trim();
    }

    /** 解析设备描述 XML，找到 WANIPConnection 或 WANPPPConnection 的 controlURL */
    private boolean parseDeviceDesc(String xml, String descURL) throws Exception {
        // 解析 base URL
        baseURL = descURL;
        int slashIdx = descURL.indexOf('/', 7); // 跳过 http://
        if (slashIdx > 0) {
            baseURL = descURL.substring(0, slashIdx);
        }

        Document doc = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        // 查找 serviceList 中的 WANIPConnection 或 WANPPPConnection
        NodeList services = doc.getElementsByTagNameNS("*", "service");
        for (int i = 0; i < services.getLength(); i++) {
            Element svc = (Element) services.item(i);
            String type = getChildText(svc, "serviceType");
            if (type != null && (type.equals(WANIP_SERVICE) || type.equals(WANPPP_SERVICE))) {
                String ctrl = getChildText(svc, "controlURL");
                if (ctrl != null) {
                    serviceType = type;
                    controlURL = resolveURL(ctrl);
                    return true;
                }
            }
        }

        // 有些路由器将 service 嵌套在 device 中（如 deviceList > device > serviceList > service）
        // 已经通过 getElementsByTagNameNS 全局查找覆盖了

        return false;
    }

    private String getChildText(Element parent, String tagName) {
        NodeList list = parent.getElementsByTagNameNS("*", tagName);
        if (list.getLength() > 0) {
            return list.item(0).getTextContent().trim();
        }
        return null;
    }

    /** 将相对 URL 解析为绝对 URL */
    private String resolveURL(String url) {
        if (url.startsWith("http://")) return url;
        if (url.startsWith("/")) return baseURL + url;
        return baseURL + "/" + url;
    }

    // ---- 内部：SOAP 请求 ----

    /** 发送 SOAP 请求到 controlURL */
    private String soapRequest(String soapAction, String body) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(controlURL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            conn.setRequestProperty("SOAPAction", "\"" + soapAction + "\"");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                return sb.toString();
            }
            // 某些路由器返回 500 但 body 中仍有有效响应
            if (code == 500) {
                try {
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();
                    return sb.toString();
                } catch (Exception ignored) {}
            }
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** GetExternalIPAddress SOAP 请求 */
    private String getExternalIPAddress() {
        String soapBody =
            "<?xml version=\"1.0\"?>\r\n" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\r\n" +
            "<s:Body>\r\n" +
            "<u:GetExternalIPAddress xmlns:u=\"" + serviceType + "\">\r\n" +
            "</u:GetExternalIPAddress>\r\n" +
            "</s:Body>\r\n" +
            "</s:Envelope>\r\n";

        String action = serviceType + "#GetExternalIPAddress";
        String response = soapRequest(action, soapBody);
        if (response != null) {
            // 从响应中提取 IP：<NewExternalIPAddress>x.x.x.x</NewExternalIPAddress>
            String tag = "NewExternalIPAddress";
            int start = response.indexOf("<" + tag);
            if (start < 0) {
                start = response.indexOf("<ns0:" + tag);
            }
            if (start >= 0) {
                start = response.indexOf(">", start) + 1;
                int end = response.indexOf("<", start);
                if (end >= 0) {
                    String ip = response.substring(start, end).trim();
                    if (ip.matches("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")) {
                        return ip;
                    }
                }
            }
        }
        return null;
    }

    /** HTTP GET 请求（用于获取设备描述 XML） */
    private String httpGet(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append("\r\n");
            reader.close();
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    // ---- 内部类型 ----

    private static class PortMapping {
        final int externalPort;
        final String protocol;
        PortMapping(int p, String proto) {
            externalPort = p;
            protocol = proto;
        }
    }
}
