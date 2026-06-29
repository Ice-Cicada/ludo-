package network;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.function.Consumer;

/**
 * TCP 客户端，连接飞行棋服务器。
 *
 * <p>连接后启动后台 reader 线程，每收到一行 JSON 就回调 {@code onMessage}。
 * 通过 {@link #send(String)} 向服务器发送消息。</p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * GameClient client = new GameClient("localhost", 9876, json -> {
 *     // 处理服务器消息
 * });
 * client.send("{\"type\":\"ROLL_DICE\"}");
 * }</pre>
 */
public class GameClient {

    private final Socket socket;
    private final PrintWriter out;
    private final BufferedReader in;
    private final Consumer<String> onMessage;
    private final Runnable onDisconnect;
    private volatile boolean running = true;

    /**
     * @param host         服务器地址
     * @param port         服务器端口
     * @param onMessage    收到服务器消息时的回调（在后台线程中调用）
     * @param onDisconnect 连接断开时的回调（在后台线程中调用）
     * @throws IOException 连接失败时抛出
     */
    public GameClient(String host, int port, Consumer<String> onMessage, Runnable onDisconnect)
            throws IOException {
        this.socket = new Socket();
        this.socket.connect(new InetSocketAddress(host, port), 5000); // 5 秒超时
        this.out = new PrintWriter(socket.getOutputStream(), true);
        this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
        this.onMessage = onMessage;
        this.onDisconnect = onDisconnect;

        Thread reader = new Thread(this::readLoop, "Client-Reader");
        reader.setDaemon(true);
        reader.start();
    }

    private void readLoop() {
        try {
            String message;
            while (running && (message = in.readLine()) != null) {
                if (onMessage != null) {
                    onMessage.accept(message);
                }
            }
        } catch (IOException e) {
            if (running && onDisconnect != null) {
                onDisconnect.run();
            }
        } finally {
            close();
        }
    }

    /** 向服务器发送一行 JSON */
    public synchronized void send(String json) {
        if (out != null && running) {
            out.println(json);
        }
    }

    /** 关闭连接 */
    public void close() {
        running = false;
        try { socket.close(); } catch (IOException ignored) { }
    }
}
