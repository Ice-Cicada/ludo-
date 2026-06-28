package server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

/**
 * 处理一个客户端连接的读写线程。
 *
 * <p>从 socket 逐行读取 JSON 消息，转发给 {@link GameServer#handleAction(int, String)}。
 * 通过 {@link #send(String)} 向该客户端发送消息。</p>
 */
public class ClientHandler implements Runnable {

    private final Socket socket;
    private final GameServer server;
    private final int playerId;
    private PrintWriter out;
    private volatile boolean running = true;

    public ClientHandler(Socket socket, GameServer server, int playerId) throws IOException {
        this.socket = socket;
        this.server = server;
        this.playerId = playerId;
        this.out = new PrintWriter(socket.getOutputStream(), true);  // 立即初始化，确保 send() 可用
    }

    public int getPlayerId() {
        return playerId;
    }

    @Override
    public void run() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"))) {
            String message;
            while (running && (message = in.readLine()) != null) {
                server.handleAction(playerId, message.trim());
            }
        } catch (IOException e) {
            if (running) {
                System.out.println("玩家 " + playerId + " 断开连接: " + e.getMessage());
                server.handleDisconnect(playerId);
            }
        } finally {
            close();
        }
    }

    /** 向该客户端发送一行 JSON */
    public synchronized void send(String json) {
        if (out != null) {
            out.println(json);
        }
    }

    /** 停止读写并关闭 socket */
    public void close() {
        running = false;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
