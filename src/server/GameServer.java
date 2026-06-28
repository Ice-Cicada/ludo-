package server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 单房间飞行棋服务器。
 *
 * <p>监听 TCP 端口，按连接顺序分配 playerId（0-3）。
 * 支持添加机器人补位，房主（player 0）手动点击开始游戏。</p>
 */
public class GameServer {

    private static final int PORT = 9876;
    private static final int MAX_PLAYERS = 4;

    private final int port;
    private final List<ClientHandler> clients = new ArrayList<>();
    private final Set<Integer> botIds = new HashSet<>();
    private final GameState gameState = new GameState();
    private volatile boolean running = true;
    private volatile boolean gameStarted = false;

    public GameServer() {
        this(PORT);
    }

    public GameServer(int port) {
        this.port = port;
    }

    /**
     * 启动服务器：进入 accept 循环。
     */
    public void start() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("飞行棋服务器已启动，端口: " + port);
            System.out.println("等待 " + MAX_PLAYERS + " 名玩家连接...");

            while (running) {
                Socket socket = serverSocket.accept();

                synchronized (this) {
                    if (clients.size() >= MAX_PLAYERS) {
                        System.out.println("房间已满，拒绝新连接: " + socket.getInetAddress());
                        socket.close();
                        continue;
                    }

                    int playerId = clients.size();
                    ClientHandler client;
                    try {
                        client = new ClientHandler(socket, this, playerId);
                    } catch (IOException e) {
                        System.out.println("创建客户端连接失败: " + e.getMessage());
                        socket.close();
                        continue;
                    }
                    clients.add(client);
                    new Thread(client, "Client-" + playerId).start();

                    // 告知该客户端其 playerId
                    client.send("{\"type\":\"ASSIGN_PLAYER\",\"playerId\":" + playerId + "}");

                    // 广播有新玩家加入
                    broadcast("{\"type\":\"PLAYER_JOINED\",\"playerCount\":" + clients.size() + "}");

                    System.out.println("玩家 " + playerId + " 已连接 (" + clients.size() + "/" + MAX_PLAYERS + ")");
                    System.out.println("  总人数(含机器人): " + totalPlayers() + "/" + MAX_PLAYERS
                            + "  (真人: " + clients.size() + ", 机器人: " + botIds.size() + ")");
                }
            }
        }
    }

    /**
     * 处理客户端发来的操作。
     * 由 ClientHandler 在读取线程中调用。
     */
    public void handleAction(int playerId, String json) {
        String type = extractJsonString(json, "type");
        if (type == null) {
            sendTo(playerId, "{\"type\":\"ERROR\",\"message\":\"无效的消息格式。\"}");
            return;
        }

        // ---- 游戏开始前的命令 ----
        if (!gameStarted) {
            switch (type) {
                case "ADD_BOT":
                    handleAddBot(playerId);
                    return;
                case "START_GAME_REQUEST":
                    handleStartGameRequest(playerId);
                    return;
                default:
                    sendTo(playerId, "{\"type\":\"ERROR\",\"message\":\"游戏尚未开始。\"}");
                    return;
            }
        }

        // ---- 游戏中命令 ----
        String error = null;

        switch (type) {
            case "ROLL_DICE":
                error = gameState.rollDice(playerId);
                break;

            case "MOVE_PIECE":
                int pieceNumber = extractJsonInt(json, "pieceNumber");
                if (pieceNumber < 1 || pieceNumber > 4) {
                    error = "棋子编号无效（需要 1-4）。";
                } else {
                    error = gameState.movePiece(playerId, pieceNumber);
                }
                break;

            default:
                error = "未知的消息类型: " + type;
        }

        if (error != null) {
            sendTo(playerId, "{\"type\":\"ERROR\",\"message\":\"" + escapeJson(error) + "\"}");
            return;
        }

        // 成功 → 广播新状态
        afterMoveBroadcast();
    }

    /** 移动后的广播 + 机器人自动回合检测 */
    private synchronized void afterMoveBroadcast() {
        if (gameState.getPhase() == GameState.Phase.GAME_OVER) {
            broadcast(gameState.buildStateJson());
            broadcast(gameState.buildGameOverJson());
            System.out.println("游戏结束！获胜者: " + gameState.getWinnerName());
            return;
        }
        broadcast(gameState.buildStateJson());

        // 若当前玩家是机器人，自动执行回合
        if (isBot(gameState.getCurrentPlayer())) {
            scheduleBotTurn();
        }
    }

    // ---- 机器人 ----

    private boolean isBot(int playerId) {
        return botIds.contains(playerId);
    }

    private int totalPlayers() {
        return clients.size() + botIds.size();
    }

    private synchronized void handleAddBot(int requesterId) {
        if (gameStarted) {
            sendTo(requesterId, "{\"type\":\"ERROR\",\"message\":\"游戏已开始，无法添加机器人。\"}");
            return;
        }
        if (totalPlayers() >= MAX_PLAYERS) {
            sendTo(requesterId, "{\"type\":\"ERROR\",\"message\":\"人数已满（4/4），无法添加机器人。\"}");
            return;
        }

        int botId = totalPlayers(); // 机器人填下一个空位
        botIds.add(botId);

        String botJson = "{\"type\":\"BOT_ADDED\",\"playerId\":" + botId
                + ",\"playerName\":\"" + gameState.getPlayers().get(botId).getName() + "(机器人)\""
                + ",\"totalCount\":" + totalPlayers() + "}";
        broadcast(botJson);

        System.out.println("机器人 " + botId + " 已添加 (" + totalPlayers() + "/" + MAX_PLAYERS + ")");
    }

    private synchronized void handleStartGameRequest(int requesterId) {
        if (gameStarted) {
            sendTo(requesterId, "{\"type\":\"ERROR\",\"message\":\"游戏已经开始。\"}");
            return;
        }
        if (requesterId != 0) {
            sendTo(requesterId, "{\"type\":\"ERROR\",\"message\":\"只有房主（玩家0）可以开始游戏。\"}");
            return;
        }
        if (totalPlayers() < MAX_PLAYERS) {
            sendTo(requesterId, "{\"type\":\"ERROR\",\"message\":\"人数不足（需要 4 人，当前 "
                    + totalPlayers() + " 人），请添加机器人。\"}");
            return;
        }

        gameStarted = true;
        System.out.println("房主开始游戏！真人: " + clients.size() + ", 机器人: " + botIds.size());
        broadcast(gameState.buildStartGameJson());
        broadcast(gameState.buildStateJson());

        // 如果第一个回合是机器人，自动执行
        if (isBot(gameState.getCurrentPlayer())) {
            scheduleBotTurn();
        }
    }

    /** 机器人自动回合（带延迟，方便人类玩家观察） */
    private void scheduleBotTurn() {
        new Thread(() -> {
            try {
                while (true) {
                    Thread.sleep(1200); // 掷骰前等待

                    int botId;
                    synchronized (GameServer.this) {
                        if (gameState.getPhase() == GameState.Phase.GAME_OVER) return;
                        if (!isBot(gameState.getCurrentPlayer())) return;
                        botId = gameState.getCurrentPlayer();

                        // 自动掷骰
                        gameState.rollDice(botId);
                        broadcast(gameState.buildStateJson());

                        // 若无可移动子 → 已自动跳回合，继续检查下一位
                        if (gameState.getPhase() != GameState.Phase.WAITING_FOR_PIECE) {
                            if (isBot(gameState.getCurrentPlayer())) continue;
                            return;
                        }
                    }

                    Thread.sleep(600); // 选子前短暂停顿

                    synchronized (GameServer.this) {
                        if (gameState.getPhase() != GameState.Phase.WAITING_FOR_PIECE) return;

                        // 随机选一枚可移动的棋子
                        List<Integer> movable = gameState.getMovablePieceNumbers();
                        if (movable.isEmpty()) return;
                        int pieceNum = movable.get((int) (Math.random() * movable.size()));

                        gameState.movePiece(botId, pieceNum);
                        afterMoveBroadcast();

                        if (gameState.getPhase() == GameState.Phase.GAME_OVER) return;

                        // 若同一机器人掷出 6 → 继续循环
                        if (gameState.getCurrentPlayer() == botId
                                && gameState.getPhase() == GameState.Phase.WAITING_FOR_ROLL) {
                            continue;
                        }

                        // 若下一位也是机器人 → 继续循环
                        if (isBot(gameState.getCurrentPlayer())) continue;

                        return;
                    }
                }
            } catch (InterruptedException ignored) {
            }
        }, "BotTurn").start();
    }

    /**
     * 处理玩家断线。
     */
    public synchronized void handleDisconnect(int playerId) {
        System.out.println("玩家 " + playerId + " 已断开。");
        clients.removeIf(c -> c.getPlayerId() == playerId);
        // 通知其他玩家
        broadcast(gameState.buildPlayerLeftJson(playerId));
    }

    /** 向单个客户端发消息 */
    private void sendTo(int playerId, String json) {
        for (ClientHandler c : clients) {
            if (c.getPlayerId() == playerId) {
                c.send(json);
                return;
            }
        }
    }

    /** 向所有客户端广播消息 */
    public synchronized void broadcast(String json) {
        for (ClientHandler c : clients) {
            c.send(json);
        }
    }

    // ---- 最小 JSON 解析（不引入第三方库） ----

    /** 从 JSON 字符串中提取 "type" 字符串字段 */
    static String extractJsonString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) return null;
        start += search.length();
        int end = json.indexOf("\"", start);
        if (end < 0) return null;
        return json.substring(start, end);
    }

    /** 从 JSON 字符串中提取整数字段 */
    static int extractJsonInt(String json, String key) {
        String search = "\"" + key + "\":";
        int start = json.indexOf(search);
        if (start < 0) return -1;
        start += search.length();
        // 跳过空白
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        if (end == start) return -1;
        try {
            return Integer.parseInt(json.substring(start, end));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ---- 入口 ----

    public static void main(String[] args) {
        int port = PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.out.println("无效端口号，使用默认端口 " + PORT);
            }
        }
        try {
            new GameServer(port).start();
        } catch (IOException e) {
            System.err.println("服务器启动失败: " + e.getMessage());
            System.exit(1);
        }
    }
}
