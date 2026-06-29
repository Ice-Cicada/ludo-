package ui;

import game.Board;
import game.Piece;
import game.Player;
import network.GameClient;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
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
    private final JTextArea historyArea = new JTextArea();

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
                        setStatusHtml("你是房主，点击「开始游戏」即可（机器人自动补齐）。");
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
                    initPlayersFromJson(json); // 从 JSON 解析实际玩家列表
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
        // 已废弃，改用 initPlayersFromJson 从 START_GAME JSON 解析
    }

    /**
     * 从 START_GAME 消息的 JSON 中解析玩家列表。
     * 格式: {"type":"START_GAME","playerCount":2,"players":[{"name":"蓝方","offset":1},...]}
     */
    private void initPlayersFromJson(String json) {
        players.clear();
        // 提取 players 数组内容
        String playersJson = extractArray(json, "players");
        if (playersJson == null) return;

        // 解析每个 {"name":"...","offset":...} 对象
        int pos = 0;
        while (pos < playersJson.length()) {
            int braceStart = playersJson.indexOf('{', pos);
            if (braceStart < 0) break;
            int braceEnd = playersJson.indexOf('}', braceStart);
            if (braceEnd < 0) break;
            String obj = playersJson.substring(braceStart + 1, braceEnd);

            String name = extractString("{" + obj + "}", "name");
            int offset = extractInt("{" + obj + "}", "offset");

            if (name != null && offset > 0) {
                players.add(new Player(name, offset));
            }
            pos = braceEnd + 1;
        }
    }

    private void syncState(String json) {
        // 解析 currentPlayer
        currentPlayerIndex = extractInt(json, "currentPlayer");
        currentDice = extractInt(json, "dice");

        String ph = extractString(json, "phase");
        if (ph != null) phase = ph;

        // 解析 pieces: [[-1,-1,-1,-1],...]（玩家人数可变）
        String piecesStr = extractArray(json, "pieces");
        if (piecesStr != null && !players.isEmpty()) {
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

        // 骰子显示：有新的掷骰结果才更新，否则保留上一个玩家的点数
        if (currentDice > 0) {
            diceLabel.setText("骰子：" + currentDice);
        } else if (currentDice == 0 && "WAITING_FOR_ROLL".equals(phase) && currentPlayerIndex == myPlayerId) {
            diceLabel.setText("骰子：-");  // 轮到自己掷骰时才显示 -
        }

        // 当前玩家标签
        if (currentPlayerIndex < players.size()) {
            Player cp = players.get(currentPlayerIndex);
            currentPlayerLabel.setText(cp.getName());
            currentPlayerLabel.setForeground(BoardPanel.playerColor(cp));
        }

        // 按钮状态（服务端校验回合，客户端只需判断是否为自己回合+ROLL阶段）
        boolean myTurn = currentPlayerIndex == myPlayerId
                && phase.contains("WAITING_FOR_ROLL");
        rollButton.setEnabled(myTurn);

        // 更新历史记录（倒序显示：最早在上，最新在下）
        String histStr = extractArray(json, "history");
        if (histStr != null) {
            // 先收集所有条目到 list
            java.util.List<String> entries = new java.util.ArrayList<>();
            int pos = 0;
            while (pos < histStr.length()) {
                int qStart = histStr.indexOf('"', pos);
                if (qStart < 0) break;
                int qEnd = histStr.indexOf('"', qStart + 1);
                if (qEnd < 0) break;
                entries.add(histStr.substring(qStart + 1, qEnd));
                pos = qEnd + 1;
            }
            // 反转：最早的在上
            java.util.Collections.reverse(entries);
            StringBuilder histText = new StringBuilder();
            for (String e : entries) {
                histText.append(e).append("\n");
            }
            historyArea.setText(histText.toString());
            // 自动滚到底部（最新）
            historyArea.setCaretPosition(historyArea.getDocument().getLength());
        }

        // 更新棋盘
        boardPanel.updateState(players, currentPlayerIndex, movablePieces);
    }

    private void syncPieces(String piecesStr) {
        // piecesStr 格式: "[[-1,-1,-1,-1],[-1,-1,-1,-1],[-1,-1,-1,-1],[-1,10,-1,-1]]"
        // 玩家数可变（1-4），每个玩家固定 4 枚棋子
        String inner = piecesStr.replace("[", "").replace("]", "");
        String[] rows = inner.split(",");
        int playerCount = players.size();
        if (rows.length >= playerCount * 4) {
            for (int i = 0; i < playerCount; i++) {
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
        JPanel sidePanel = new JPanel(new BorderLayout(0, 10));
        sidePanel.setBackground(new Color(246, 248, 250));
        sidePanel.setBorder(BorderFactory.createEmptyBorder(16, 8, 16, 16));
        sidePanel.setPreferredSize(new Dimension(250, 1));

        // ---- 上半区：玩家信息 + 骰子 + 按钮 ----
        JPanel northPanel = new JPanel(new BorderLayout(0, 8));
        northPanel.setOpaque(false);

        JPanel infoPanel = new JPanel(new BorderLayout(0, 4));
        infoPanel.setOpaque(false);
        currentPlayerLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 24));
        currentPlayerLabel.setHorizontalAlignment(SwingConstants.CENTER);
        diceLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 28));
        diceLabel.setHorizontalAlignment(SwingConstants.CENTER);
        infoPanel.add(currentPlayerLabel, BorderLayout.NORTH);
        infoPanel.add(diceLabel, BorderLayout.CENTER);

        rollButton.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
        rollButton.setFocusPainted(false);
        rollButton.setBackground(new Color(17, 24, 39));
        rollButton.setForeground(Color.WHITE);
        rollButton.setEnabled(false);
        rollButton.addActionListener(e -> {
            if (client != null) client.send("{\"type\":\"ROLL_DICE\"}");
        });
        infoPanel.add(rollButton, BorderLayout.SOUTH);

        northPanel.add(infoPanel, BorderLayout.NORTH);

        // 准备按钮区
        JPanel prepPanel = new JPanel(new BorderLayout(0, 6));
        prepPanel.setOpaque(false);
        prepPanel.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));

        botButton.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        botButton.setFocusPainted(false);
        botButton.setBackground(new Color(75, 85, 99));
        botButton.setForeground(Color.WHITE);
        botButton.addActionListener(e -> {
            if (client != null) client.send("{\"type\":\"ADD_BOT\"}");
        });
        prepPanel.add(botButton, BorderLayout.NORTH);

        startButton.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        startButton.setFocusPainted(false);
        startButton.setBackground(new Color(22, 163, 74));
        startButton.setForeground(Color.WHITE);
        startButton.setEnabled(false);
        startButton.addActionListener(e -> {
            if (client != null) client.send("{\"type\":\"START_GAME_REQUEST\"}");
        });
        prepPanel.add(startButton, BorderLayout.SOUTH);

        northPanel.add(prepPanel, BorderLayout.SOUTH);

        // ---- 中间：状态信息 ----
        JPanel centerPanel = new JPanel(new BorderLayout(0, 6));
        centerPanel.setOpaque(false);

        statusLabel.setVerticalAlignment(SwingConstants.TOP);
        statusLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        statusLabel.setBorder(BorderFactory.createEmptyBorder(10, 0, 4, 0));
        statusLabel.setForeground(new Color(31, 41, 55));
        centerPanel.add(statusLabel, BorderLayout.NORTH);

        // 历史记录面板
        historyArea.setEditable(false);
        historyArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        historyArea.setBackground(new Color(248, 250, 252));
        historyArea.setForeground(new Color(55, 65, 81));
        historyArea.setLineWrap(true);
        historyArea.setWrapStyleWord(true);
        JScrollPane historyScroll = new JScrollPane(historyArea);
        historyScroll.setBorder(BorderFactory.createLineBorder(new Color(203, 213, 225)));
        historyScroll.setPreferredSize(new Dimension(220, 180));
        historyScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        centerPanel.add(historyScroll, BorderLayout.CENTER);

        // ---- 底部：规则提示 ----
        JLabel hintLabel = new JLabel("<html><b>联机模式</b><br>房主（玩家0）可开始游戏<br>支持 1-4 人对战<br>可添加机器人补位<br>5 或 6 起飞<br>⤴ 跳子 ✈ 飞棋<br>掷到 6 可再来一次</html>");
        hintLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        hintLabel.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        hintLabel.setForeground(new Color(75, 85, 99));

        sidePanel.add(northPanel, BorderLayout.NORTH);
        sidePanel.add(centerPanel, BorderLayout.CENTER);
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
        if (args.length >= 1) {
            // 命令行模式：直接连接（向后兼容）
            String host = args[0];
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
        } else {
            // 无参数：弹出连接对话框
            SwingUtilities.invokeLater(() -> {
                ConnectDialog dialog = new ConnectDialog(null);
                dialog.setVisible(true);
                if (dialog.isConfirmed()) {
                    new NetworkLudoFrame(dialog.getHost(), dialog.getPort()).setVisible(true);
                } else {
                    System.exit(0);
                }
            });
        }
    }

    /**
     * 连接对话框 —— 让用户输入服务器地址和端口。
     */
    private static class ConnectDialog extends JDialog {
        private boolean confirmed = false;
        private String host = "localhost";
        private int port = 9876;

        private final JTextField hostField = new JTextField("localhost", 20);
        private final JTextField portField = new JTextField("9876", 6);

        ConnectDialog(JFrame parent) {
            super(parent, "连接服务器", true);
            setDefaultCloseOperation(DISPOSE_ON_CLOSE);
            setResizable(false);

            JPanel panel = new JPanel(new BorderLayout(10, 12));
            panel.setBorder(BorderFactory.createEmptyBorder(20, 24, 16, 24));
            panel.setBackground(new Color(246, 248, 250));

            // 标题
            JLabel title = new JLabel("飞行棋联机");
            title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
            title.setHorizontalAlignment(SwingConstants.CENTER);
            panel.add(title, BorderLayout.NORTH);

            // 输入区
            JPanel inputPanel = new JPanel(new BorderLayout(8, 8));
            inputPanel.setOpaque(false);

            JPanel addrPanel = new JPanel(new BorderLayout(4, 4));
            addrPanel.setOpaque(false);
            addrPanel.add(new JLabel("服务器地址："), BorderLayout.NORTH);
            hostField.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 16));
            addrPanel.add(hostField, BorderLayout.CENTER);

            JPanel portPanel = new JPanel(new BorderLayout(4, 4));
            portPanel.setOpaque(false);
            portPanel.add(new JLabel("端口："), BorderLayout.NORTH);
            portField.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 16));
            portPanel.add(portField, BorderLayout.CENTER);

            inputPanel.add(addrPanel, BorderLayout.CENTER);
            inputPanel.add(portPanel, BorderLayout.EAST);

            panel.add(inputPanel, BorderLayout.CENTER);

            // 按钮
            JPanel btnPanel = new JPanel(new BorderLayout(10, 0));
            btnPanel.setOpaque(false);

            JButton cancelBtn = new JButton("取消");
            cancelBtn.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
            cancelBtn.addActionListener(e -> dispose());

            JButton connectBtn = new JButton("连接");
            connectBtn.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
            connectBtn.setBackground(new Color(22, 163, 74));
            connectBtn.setForeground(Color.WHITE);
            connectBtn.setFocusPainted(false);
            connectBtn.addActionListener(e -> {
                String h = hostField.getText().trim();
                if (h.isEmpty()) {
                    hostField.setBorder(BorderFactory.createLineBorder(Color.RED, 2));
                    return;
                }
                int p;
                try {
                    p = Integer.parseInt(portField.getText().trim());
                    if (p < 1 || p > 65535) throw new NumberFormatException();
                } catch (NumberFormatException ex) {
                    portField.setBorder(BorderFactory.createLineBorder(Color.RED, 2));
                    return;
                }
                host = h;
                port = p;
                confirmed = true;
                dispose();
            });

            // Enter 键触发连接
            portField.addActionListener(connectBtn.getActionListeners()[0]);
            hostField.addActionListener(connectBtn.getActionListeners()[0]);

            btnPanel.add(cancelBtn, BorderLayout.WEST);
            btnPanel.add(connectBtn, BorderLayout.EAST);
            panel.add(btnPanel, BorderLayout.SOUTH);

            // 提示
            JLabel hint = new JLabel("输入房主的服务器地址和端口号");
            hint.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            hint.setForeground(new Color(107, 114, 128));
            hint.setHorizontalAlignment(SwingConstants.CENTER);
            hint.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
            panel.add(hint, BorderLayout.SOUTH);

            add(panel);
            pack();
            setLocationRelativeTo(parent);

            // 默认焦点
            hostField.selectAll();
            hostField.requestFocusInWindow();
        }

        boolean isConfirmed() { return confirmed; }
        String getHost() { return host; }
        int getPort() { return port; }
    }
}
