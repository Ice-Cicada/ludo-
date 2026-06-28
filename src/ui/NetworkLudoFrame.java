package ui;

import game.Board;
import game.Piece;
import game.Player;
import network.GameClient;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 网络对战客户端 Swing 窗口。
 *
 * <p>连接服务器后，所有游戏逻辑由服务端驱动。
 * 客户端只负责：接收 STATE → 同步本地数据 → 渲染棋盘 / 发送操作。</p>
 *
 * <p>使用方式：</p>
 * <pre>{@code
 * SwingUtilities.invokeLater(() -> {
 *     new NetworkLudoFrame("localhost", 9876).setVisible(true);
 * });
 * }</pre>
 */
public class NetworkLudoFrame extends JFrame {

    // ---- 网络 ----
    private GameClient client;
    private int myPlayerId = -1;

    // ---- 本地游戏镜像（从服务器 STATE 同步） ----
    private final Board board = new Board();  // 仅用于 BoardPanel 渲染
    private final List<Player> players = new ArrayList<>();
    private int currentPlayerIndex = 0;
    private int currentDice = 0;
    private String phase = "WAITING_FOR_ROLL";
    private final Set<Piece> movablePieces = new HashSet<>();

    // ---- UI 组件 ----
    private final BoardPanel boardPanel;
    private final JLabel currentPlayerLabel = new JLabel();
    private final JLabel diceLabel = new JLabel("骰子：-");
    private final JLabel statusLabel = new JLabel("正在连接服务器...");
    private final JButton rollButton = new JButton("掷骰子");
    private final JButton botButton = new JButton("添加机器人");
    private final JButton startButton = new JButton("开始游戏");

    private boolean preGame = true;  // 游戏开始前为 true，开始后隐藏准备按钮

    private final String host;
    private final int port;

    public NetworkLudoFrame(String host, int port) {
        super("飞行棋 - 联机模式");
        this.host = host;
        this.port = port;

        boardPanel = new BoardPanel(board, this::onPieceClicked, this::setStatusHtml);

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(980, 720));
        setLocationByPlatform(true);
        getContentPane().setBackground(new Color(229, 231, 235));
        setLayout(new BorderLayout(12, 12));

        add(boardPanel, BorderLayout.CENTER);
        add(createSidePanel(), BorderLayout.EAST);

        pack();

        // 异步连接服务器
        new Thread(this::connectToServer, "Connect").start();
    }

    // ---- 连接 ----

    private void connectToServer() {
        try {
            client = new GameClient(host, port, this::onServerMessage, this::onDisconnect);
        } catch (IOException e) {
            SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(this,
                        "无法连接到服务器 " + host + ":" + port + "\n" + e.getMessage(),
                        "连接失败", JOptionPane.ERROR_MESSAGE);
                dispose();
            });
        }
    }

    private void onDisconnect() {
        SwingUtilities.invokeLater(() -> {
            setStatusHtml("与服务器断开连接。");
            rollButton.setEnabled(false);
        });
    }

    // ---- 服务器消息处理 ----

    private void onServerMessage(String json) {
        String type = extractString(json, "type");
        if (type == null) return;

        switch (type) {
            case "ASSIGN_PLAYER":
                myPlayerId = extractInt(json, "playerId");
                SwingUtilities.invokeLater(() -> {
                    setTitle("飞行棋 - 联机模式 [玩家" + myPlayerId + "]");
                    if (myPlayerId == 0) {
                        startButton.setEnabled(true);
                        setStatusHtml("你是房主，请添加机器人并点击「开始游戏」。");
                    } else {
                        setStatusHtml("等待房主开始游戏...");
                    }
                });
                break;

            case "PLAYER_JOINED":
                int count = extractInt(json, "playerCount");
                SwingUtilities.invokeLater(() -> {
                    setStatusHtml("等待玩家加入... (" + count + "/4)");
                });
                break;

            case "BOT_ADDED":
                int botTotal = extractInt(json, "totalCount");
                String botName = extractString(json, "playerName");
                SwingUtilities.invokeLater(() -> {
                    setStatusHtml("已添加 " + (botName != null ? botName : "机器人")
                            + "（" + botTotal + "/4）");
                });
                break;

            case "START_GAME":
                SwingUtilities.invokeLater(() -> {
                    initPlayersFromStart();
                    preGame = false;
                    botButton.setVisible(false);
                    startButton.setVisible(false);
                });
                break;

            case "STATE":
                SwingUtilities.invokeLater(() -> syncState(json));
                break;

            case "GAME_OVER":
                int winnerId = extractInt(json, "winnerId");
                SwingUtilities.invokeLater(() -> {
                    String name = (winnerId >= 0 && winnerId < players.size())
                            ? players.get(winnerId).getName() : "?";
                    setStatusHtml("<b>" + name + " 获胜！</b>");
                    rollButton.setEnabled(false);
                });
                break;

            case "ERROR":
                String msg = extractString(json, "message");
                SwingUtilities.invokeLater(() -> {
                    setStatusHtml("<font color='red'>" + (msg != null ? msg : "未知错误") + "</font>");
                });
                break;

            case "PLAYER_LEFT":
                SwingUtilities.invokeLater(() -> {
                    setStatusHtml("有玩家断开了连接。");
                });
                break;
        }
    }

    // ---- 状态同步 ----

    private void initPlayersFromStart() {
        // 服务器已广播 START_GAME，重建 Player 列表
        // Player 信息从 STATE 的 pieces 数组推断（不需要显式反序列化）
        // 使用标准 4 色配置
        players.clear();
        players.add(new Player("蓝方", 1));
        players.add(new Player("绿方", 14));
        players.add(new Player("红方", 27));
        players.add(new Player("黄方", 40));
    }

    private void syncState(String json) {
        // 解析 currentPlayer
        currentPlayerIndex = extractInt(json, "currentPlayer");
        currentDice = extractInt(json, "dice");

        String ph = extractString(json, "phase");
        if (ph != null) phase = ph;

        // 解析 pieces: [[-1,-1,-1,-1],...]
        String piecesStr = extractArray(json, "pieces");
        if (piecesStr != null && players.size() == 4) {
            syncPieces(piecesStr);
        }

        // 解析 movablePieces: [1,3]
        String movStr = extractArray(json, "movablePieces");
        movablePieces.clear();
        if (movStr != null && !movStr.isEmpty() && currentPlayerIndex < players.size()) {
            Player cp = players.get(currentPlayerIndex);
            for (int num : parseIntArray(movStr)) {
                for (Piece p : cp.getPieces()) {
                    if (p.getNumber() == num) {
                        movablePieces.add(p);
                        break;
                    }
                }
            }
        }

        // 状态显示
        String lastAction = extractString(json, "lastAction");
        String display = lastAction != null ? lastAction : "";
        if (phase.contains("WAITING_FOR_ROLL") && currentPlayerIndex == myPlayerId) {
            display = "轮到你了，请掷骰子。";
        } else if (phase.contains("WAITING_FOR_PIECE") && currentPlayerIndex == myPlayerId) {
            display = "请选择一个发光的棋子。";
        } else if (phase.contains("WAITING_FOR_ROLL") && currentPlayerIndex != myPlayerId) {
            display = "等待 " + getPlayerName(currentPlayerIndex) + " 掷骰子...";
        } else if (phase.contains("WAITING_FOR_PIECE") && currentPlayerIndex != myPlayerId) {
            display = "等待 " + getPlayerName(currentPlayerIndex) + " 选子...";
        }
        final String finalDisplay = display;
        setStatusHtml(finalDisplay);

        // 骰子显示
        diceLabel.setText("骰子：" + (currentDice > 0 ? String.valueOf(currentDice) : "-"));

        // 当前玩家标签
        if (currentPlayerIndex < players.size()) {
            Player cp = players.get(currentPlayerIndex);
            currentPlayerLabel.setText(cp.getName());
            currentPlayerLabel.setForeground(BoardPanel.playerColor(cp));
        }

        // 按钮状态
        boolean myTurn = currentPlayerIndex == myPlayerId
                && phase.contains("WAITING_FOR_ROLL")
                && currentDice == 0;  // 未掷骰
        rollButton.setEnabled(myTurn);

        // 更新棋盘
        boardPanel.updateState(players, currentPlayerIndex, movablePieces);
    }

    private void syncPieces(String piecesStr) {
        // piecesStr 格式: "[[-1,-1,-1,-1],[-1,-1,-1,-1],[-1,-1,-1,-1],[-1,10,-1,-1]]"
        // 提取每个内层数组
        String inner = piecesStr.replace("[", "").replace("]", "");
        String[] rows = inner.split(",");
        // rows 会有 16 个值: player 0's 4 pieces, player 1's 4 pieces, etc.
        if (rows.length >= 16) {
            for (int i = 0; i < 4 && i < players.size(); i++) {
                List<Piece> pieces = players.get(i).getPieces();
                for (int j = 0; j < 4 && j < pieces.size(); j++) {
                    int progress = Integer.parseInt(rows[i * 4 + j].trim());
                    pieces.get(j).setProgress(progress);
                }
            }
        }
    }

    // ---- UI 交互 ----

    private void onPieceClicked(Piece piece) {
        // 仅当己方回合且 phase 为 WAITING_FOR_PIECE 时有效
        if (currentPlayerIndex != myPlayerId) return;
        if (!phase.contains("WAITING_FOR_PIECE")) return;
        if (!movablePieces.contains(piece)) return;

        client.send("{\"type\":\"MOVE_PIECE\",\"pieceNumber\":" + piece.getNumber() + "}");
    }

    private JPanel createSidePanel() {
        JPanel sidePanel = new JPanel(new BorderLayout(10, 10));
        sidePanel.setBackground(new Color(246, 248, 250));
        sidePanel.setBorder(BorderFactory.createEmptyBorder(16, 8, 16, 16));
        sidePanel.setPreferredSize(new Dimension(236, 1));

        JPanel topPanel = new JPanel(new BorderLayout(0, 12));
        topPanel.setOpaque(false);
        currentPlayerLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 24));
        currentPlayerLabel.setHorizontalAlignment(SwingConstants.CENTER);
        diceLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 28));
        diceLabel.setHorizontalAlignment(SwingConstants.CENTER);
        topPanel.add(currentPlayerLabel, BorderLayout.NORTH);
        topPanel.add(diceLabel, BorderLayout.CENTER);

        rollButton.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
        rollButton.setFocusPainted(false);
        rollButton.setBackground(new Color(17, 24, 39));
        rollButton.setForeground(Color.WHITE);
        rollButton.setEnabled(false);
        rollButton.addActionListener(e -> {
            if (client != null) {
                client.send("{\"type\":\"ROLL_DICE\"}");
            }
        });
        topPanel.add(rollButton, BorderLayout.SOUTH);

        // 游戏开始前的准备按钮区域
        JPanel prepPanel = new JPanel(new BorderLayout(8, 8));
        prepPanel.setOpaque(false);
        prepPanel.setBorder(BorderFactory.createEmptyBorder(16, 0, 0, 0));

        botButton.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        botButton.setFocusPainted(false);
        botButton.setBackground(new Color(75, 85, 99));
        botButton.setForeground(Color.WHITE);
        botButton.addActionListener(e -> {
            if (client != null) client.send("{\"type\":\"ADD_BOT\"}");
        });
        prepPanel.add(botButton, BorderLayout.NORTH);

        startButton.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        startButton.setFocusPainted(false);
        startButton.setBackground(new Color(22, 163, 74));
        startButton.setForeground(Color.WHITE);
        startButton.setEnabled(false);
        startButton.addActionListener(e -> {
            if (client != null) client.send("{\"type\":\"START_GAME_REQUEST\"}");
        });
        prepPanel.add(startButton, BorderLayout.SOUTH);

        statusLabel.setVerticalAlignment(SwingConstants.TOP);
        statusLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
        statusLabel.setBorder(BorderFactory.createEmptyBorder(20, 0, 0, 0));
        statusLabel.setForeground(new Color(31, 41, 55));

        JLabel hintLabel = new JLabel("<html><b>联机模式</b><br>房主（玩家0）可开始游戏<br>添加机器人补齐 4 人<br>5 或 6 起飞<br>⤴ 跳子 ✈ 飞棋<br>掷到 6 可再来一次</html>");
        hintLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        hintLabel.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
        hintLabel.setForeground(new Color(75, 85, 99));

        sidePanel.add(topPanel, BorderLayout.NORTH);
        sidePanel.add(prepPanel, BorderLayout.CENTER);
        sidePanel.add(statusLabel, BorderLayout.EAST);
        sidePanel.add(hintLabel, BorderLayout.SOUTH);
        return sidePanel;
    }

    private void setStatusHtml(String html) {
        statusLabel.setText("<html>" + html + "</html>");
    }

    private String getPlayerName(int id) {
        if (id >= 0 && id < players.size()) {
            return players.get(id).getName();
        }
        return "玩家" + id;
    }

    // ---- 最小 JSON 解析（不依赖第三方库） ----

    private static String extractString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) return null;
        start += search.length();
        int end = json.indexOf("\"", start);
        if (end < 0) return null;
        return json.substring(start, end);
    }

    private static int extractInt(String json, String key) {
        String search = "\"" + key + "\":";
        int start = json.indexOf(search);
        if (start < 0) return -1;
        start += search.length();
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        if (end == start) return -1;
        try {
            return Integer.parseInt(json.substring(start, end));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 提取 JSON 数组字符串（最外层 [...]），用于 pieces 和 movablePieces */
    private static String extractArray(String json, String key) {
        String search = "\"" + key + "\":";
        int start = json.indexOf(search);
        if (start < 0) return null;
        start += search.length();
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start >= json.length() || json.charAt(start) != '[') return null;
        int depth = 0;
        int end = start;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) break;
            }
            end++;
        }
        if (depth != 0) return null;
        return json.substring(start + 1, end); // 去掉外层 [ ]
    }

    /** 将 "[1,3]" 或 "1,3" 格式的字符串解析为 int[] */
    private static int[] parseIntArray(String str) {
        String[] parts = str.split(",");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                result[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                result[i] = -1;
            }
        }
        return result;
    }

    // ---- 入口 ----

    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = 9876;
        if (args.length > 1) {
            try {
                port = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                System.out.println("无效端口号，使用默认端口 " + port);
            }
        }
        final String h = host;
        final int p = port;
        SwingUtilities.invokeLater(() -> {
            new NetworkLudoFrame(h, p).setVisible(true);
        });
    }
}
