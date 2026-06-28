package ui;

import game.Board;
import game.Dice;
import game.MoveResult;
import game.Piece;
import game.Player;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class LudoFrame extends JFrame {
    private final Board board = new Board();
    private final Dice dice = new Dice();
    private final List<Player> players = new ArrayList<>();
    private final Set<Piece> movablePieces = new HashSet<>();

    private final BoardPanel boardPanel = new BoardPanel(board, this::movePiece, this::setStatus);
    private final JLabel currentPlayerLabel = new JLabel();
    private final JLabel diceLabel = new JLabel("骰子：-");
    private final JLabel statusLabel = new JLabel("点击“掷骰子”开始游戏。");
    private final JButton rollButton = new JButton("掷骰子");

    private int currentPlayerIndex = 0;
    private int currentRoll = 0;
    private boolean waitingForPiece = false;

    public LudoFrame() {
        super("Java 飞行棋");
        players.add(new Player("蓝方", 1));
        players.add(new Player("绿方", 14));
        players.add(new Player("红方", 27));
        players.add(new Player("黄方", 40));

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(980, 720));
        setLocationByPlatform(true);
        getContentPane().setBackground(new Color(229, 231, 235));
        setLayout(new BorderLayout(12, 12));

        add(boardPanel, BorderLayout.CENTER);
        add(createSidePanel(), BorderLayout.EAST);

        refreshHeader();
        pack();
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
        rollButton.addActionListener(event -> rollDice());
        topPanel.add(rollButton, BorderLayout.SOUTH);

        statusLabel.setVerticalAlignment(SwingConstants.TOP);
        statusLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
        statusLabel.setBorder(BorderFactory.createEmptyBorder(20, 0, 0, 0));
        statusLabel.setForeground(new Color(31, 41, 55));

        JLabel hintLabel = new JLabel("<html><b>玩法</b><br>5 或 6 起飞<br>点击发光棋子移动<br>掷到 6 可再来一次<br><br><b>特殊规则</b><br>⤴ 跳子：落在同色格<br>自动前进 4 步<br>✈ 飞棋：落在飞点<br>传送到配对格</html>");
        hintLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        hintLabel.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
        hintLabel.setForeground(new Color(75, 85, 99));

        sidePanel.add(topPanel, BorderLayout.NORTH);
        sidePanel.add(statusLabel, BorderLayout.CENTER);
        sidePanel.add(hintLabel, BorderLayout.SOUTH);
        return sidePanel;
    }

    private void rollDice() {
        if (waitingForPiece) {
            setStatus("请先选择一个发光的棋子。");
            return;
        }

        currentRoll = dice.roll();
        diceLabel.setText("骰子：" + currentRoll);

        Player player = currentPlayer();
        movablePieces.clear();
        for (Piece piece : player.getPieces()) {
            if (board.canMove(piece, currentRoll)) {
                movablePieces.add(piece);
            }
        }

        if (movablePieces.isEmpty()) {
            setStatus(player.getName() + " 掷出 " + currentRoll + "，没有棋子可以移动。");
            rollButton.setEnabled(false);
            boardPanel.updateState(players, currentPlayerIndex, movablePieces);
            new Timer(900, event -> {
                ((Timer) event.getSource()).stop();
                nextPlayer();
                rollButton.setEnabled(true);
            }).start();
            return;
        }

        waitingForPiece = true;
        rollButton.setEnabled(false);
        setStatus(player.getName() + " 掷出 " + currentRoll + "，请选择一个发光棋子。");
        boardPanel.updateState(players, currentPlayerIndex, movablePieces);
    }

    private void movePiece(Piece piece) {
        if (!waitingForPiece || !movablePieces.contains(piece)) {
            return;
        }

        Player player = currentPlayer();
        MoveResult result = board.move(player, piece, currentRoll, players);
        waitingForPiece = false;
        movablePieces.clear();

        String message = player.getName() + " 的 " + piece.getNumber() + " 号棋子移动到 "
                + describePiecePosition(player, piece) + "。";
        if (result.jumped()) {
            message += "<br>⤴ 跳子！（落在同色格，自动前进 4 步）";
        }
        if (result.flew()) {
            message += "<br>✈ 飞棋！（落在飞点，传送到配对格）";
        }
        if (result.capturedPieces() > 0) {
            message += "<br>踩回对手 " + result.capturedPieces() + " 枚棋子。";
        }
        if (result.finished()) {
            message += "<br>这枚棋子到达终点！";
        }

        if (player.hasWon()) {
            setStatus(message + "<br><br><b>" + player.getName() + " 获胜！</b>");
            diceLabel.setText("胜利！");
            rollButton.setEnabled(false);
            boardPanel.updateState(players, currentPlayerIndex, movablePieces);
            return;
        }

        if (currentRoll == 6) {
            setStatus(message + "<br>掷到 6，" + player.getName() + " 再来一次。");
            rollButton.setEnabled(true);
        } else {
            setStatus(message);
            nextPlayer();
            rollButton.setEnabled(true);
        }

        boardPanel.updateState(players, currentPlayerIndex, movablePieces);
    }

    private void nextPlayer() {
        currentPlayerIndex = (currentPlayerIndex + 1) % players.size();
        currentRoll = 0;
        diceLabel.setText("骰子：-");
        refreshHeader();
        boardPanel.updateState(players, currentPlayerIndex, movablePieces);
    }

    private Player currentPlayer() {
        return players.get(currentPlayerIndex);
    }

    private void refreshHeader() {
        Player player = currentPlayer();
        currentPlayerLabel.setText(player.getName());
        currentPlayerLabel.setForeground(BoardPanel.playerColor(player));
    }

    private void setStatus(String html) {
        statusLabel.setText("<html>" + html + "</html>");
    }

    private String translatePosition(String position) {
        if ("base".equals(position)) {
            return "基地";
        }
        if ("launch pad".equals(position)) {
            return "起飞台";
        }
        if ("finished".equals(position)) {
            return "终点";
        }
        if (position.startsWith("home lane ")) {
            return "家门跑道 " + position.substring("home lane ".length());
        }
        if (position.startsWith("track square ")) {
            return "公共轨道 " + position.substring("track square ".length()) + " 格";
        }
        return position;
    }

    private String describePiecePosition(Player player, Piece piece) {
        if (piece.isOnLaunchPad()) {
            return "起飞台";
        }
        return translatePosition(board.describePosition(player, piece));
    }

}
