package server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * 最小化 Redis 客户端（RESP 协议，无第三方依赖）。
 * Redis 不可用时自动降级为内存存储。
 */
public class RedisClient {

    private static final String KEY = "ludo:history";
    private static final int MAX_HISTORY = 50;

    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private boolean connected;
    private final LinkedList<String> fallback = new LinkedList<>();

    public RedisClient(String host, int port) {
        try {
            socket = new Socket(host, port);
            out = new PrintWriter(socket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            connected = send("PING") != null;
            System.out.println("Redis: " + (connected ? "已连接" : "无响应，使用内存存储"));
        } catch (IOException e) {
            connected = false;
            System.out.println("Redis 不可用 (" + e.getMessage() + ")，使用内存存储");
        }
        if (connected) del(KEY);
        else fallback.clear();
    }

    /** 追加一条记录 */
    public void pushHistory(String entry) {
        if (connected) {
            send("LPUSH", KEY, entry);
            // 裁剪长度
            send("LTRIM", KEY, "0", String.valueOf(MAX_HISTORY - 1));
        } else {
            fallback.addFirst(entry);
            while (fallback.size() > MAX_HISTORY) fallback.removeLast();
        }
    }

    /** 获取最近 N 条 */
    public List<String> getHistory(int count) {
        if (connected) return lrange(KEY, 0, count - 1);
        List<String> r = new ArrayList<>();
        int n = Math.min(count, fallback.size());
        for (int i = 0; i < n; i++) r.add(fallback.get(i));
        return r;
    }

    public void close() {
        connected = false;
        try { socket.close(); } catch (IOException ignored) { }
    }

    // ---- RESP 底层 ----

    private synchronized String send(String cmd, String... args) {
        if (!connected) return null;
        try {
            int argc = 1 + args.length;
            StringBuilder sb = new StringBuilder();
            sb.append("*").append(argc).append("\r\n");
            writeArg(sb, cmd);
            for (String a : args) writeArg(sb, a);
            out.write(sb.toString());
            out.flush();
            return readResp();
        } catch (IOException e) {
            connected = false;
            System.err.println("Redis 断开: " + e.getMessage());
            return null;
        }
    }

    private void writeArg(StringBuilder sb, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        sb.append("$").append(b.length).append("\r\n");
        sb.append(s).append("\r\n");
    }

    /** 读取一个 RESP 回复 */
    private String readResp() throws IOException {
        int ch = in.read();
        if (ch < 0) throw new IOException("连接关闭");
        return switch (ch) {
            case '+' -> in.readLine();
            case '-' -> "ERR:" + in.readLine();
            case ':' -> in.readLine();
            case '$' -> {
                int len = Integer.parseInt(in.readLine());
                if (len < 0) yield null;
                char[] buf = new char[len];
                in.read(buf, 0, len);
                in.readLine(); // \r\n
                yield new String(buf);
            }
            case '*' -> {
                int n = Integer.parseInt(in.readLine());
                if (n <= 0) yield "";
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < n; i++) {
                    String e = readResp();
                    if (e != null) sb.append(e).append("\n");
                }
                yield sb.toString().trim();
            }
            default -> throw new IOException("未知响应: " + (char) ch);
        };
    }

    private List<String> lrange(String key, int start, int stop) {
        String resp = send("LRANGE", key, String.valueOf(start), String.valueOf(stop));
        List<String> r = new ArrayList<>();
        if (resp != null && !resp.startsWith("ERR")) {
            for (String line : resp.split("\n")) if (!line.isEmpty()) r.add(line);
        }
        return r;
    }

    private void del(String key) { send("DEL", key); }
}
