package game;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * 游戏 —— 流程编排层，负责回合循环和 I/O 交互。
 *
 * 职责：
 *   1. 持有 Board（规则）、Dice（随机）、Players（状态）
 *   2. 驱动回合循环：打印 → 等待 → 掷骰 → 选子 → 移动 → 判断胜负 → 下一位
 *   3. 所有 I/O（System.out / Scanner）集中在此类，Board 和 Piece 不涉及输入输出
 *
 * 回合流转（start 方法的主循环）：
 *   printBoard → waitForEnter → dice.roll → board.canMove 筛可选子
 *   → choosePiece（玩家选子）→ board.move 执行 → 检查 hasWon / 6 连掷
 *
 * @see Board    —— 移动规则和踩子逻辑
 * @see Player   —— 持有棋子和起点偏移
 * @see Piece    —— 每枚棋子的 progress 状态
 * @see Dice     —— 随机数
 */
public class Game {
    /** 规则引擎 —— 无状态，只负责判定和换算 */
    private final Board board = new Board();
    /** 骰子 —— 封装 Random */
    private final Dice dice = new Dice();
    /** 四位玩家：蓝(1) 绿(14) 红(27) 黄(40) */
    private final List<Player> players = new ArrayList<>();
    /** 控制台输入，整个游戏共用一个 Scanner */
    private final Scanner scanner = new Scanner(System.in);

    public Game() {
        // startOffset 指向三角块旁边的同色起点格，四个起点相隔 13 格。
        players.add(new Player("Blue", 1));
        players.add(new Player("Green", 14));
        players.add(new Player("Red", 27));
        players.add(new Player("Yellow", 40));
    }

    /**
     * 主循环：驱动整个游戏直到有人获胜或玩家退出。
     */
    public void start() {
        System.out.println("Flying Chess / Ludo Console MVP");
        System.out.println("Press Enter to roll. Type q at any prompt to quit.");

        int currentPlayerIndex = 0;
        while (true) {
            Player currentPlayer = players.get(currentPlayerIndex);

            // 1. 显示棋盘
            printBoard();
            System.out.println();
            System.out.println(currentPlayer.getName() + "'s turn.");
            waitForEnter();

            // 2. 掷骰子
            int roll = dice.roll();
            System.out.println(currentPlayer.getName() + " rolled " + roll + ".");

            // 3. 筛选可移动棋子（委托给 Board.canMove）
            List<Piece> movablePieces = movablePieces(currentPlayer, roll);
            if (movablePieces.isEmpty()) {
                System.out.println("No available move.");
                currentPlayerIndex = nextPlayerIndex(currentPlayerIndex);
                continue;
            }

            // 4. 玩家选择棋子
            Piece selectedPiece = choosePiece(currentPlayer, movablePieces, roll);

            // 5. 执行移动 + 踩子（委托给 Board.move）
            MoveResult result = board.move(currentPlayer, selectedPiece, roll, players);
            System.out.println(currentPlayer.getName() + " moved piece " + selectedPiece.getNumber()
                    + " to " + board.describePosition(currentPlayer, selectedPiece) + ".");

            if (result.jumped()) {
                System.out.println("  ⤴ Jumped! (landed on own color square, auto-advanced 4)");
            }
            if (result.flew()) {
                System.out.println("  ✈ Flew! (landed on fly point, teleported to paired square)");
            }
            if (result.capturedPieces() > 0) {
                System.out.println("Captured " + result.capturedPieces() + " opponent piece(s).");
            }

            // 6. 判断胜负
            if (currentPlayer.hasWon()) {
                printBoard();
                System.out.println();
                System.out.println(currentPlayer.getName() + " wins!");
                return;
            }

            // 7. 掷出 6 则连掷，否则轮到下一位
            if (roll == 6) {
                System.out.println(currentPlayer.getName() + " rolled a 6 and gets another turn.");
            } else {
                currentPlayerIndex = nextPlayerIndex(currentPlayerIndex);
            }
        }
    }

    /** 打印所有玩家所有棋子的当前位置 */
    private void printBoard() {
        System.out.println();
        System.out.println("Current pieces:");
        for (Player player : players) {
            System.out.print(player.getName() + ": ");
            for (Piece piece : player.getPieces()) {
                System.out.print("#" + piece.getNumber() + "=" + board.describePosition(player, piece) + "  ");
            }
            System.out.println();
        }
    }

    /** 筛选当前玩家可以移动的棋子（委托 Board.canMove） */
    private List<Piece> movablePieces(Player player, int roll) {
        List<Piece> movablePieces = new ArrayList<>();
        for (Piece piece : player.getPieces()) {
            if (board.canMove(piece, roll)) {
                movablePieces.add(piece);
            }
        }
        return movablePieces;
    }

    /** 让玩家在可选棋子中选择一枚（循环直到输入有效编号或退出） */
    private Piece choosePiece(Player player, List<Piece> movablePieces, int roll) {
        while (true) {
            System.out.println("Choose a piece to move:");
            for (Piece piece : movablePieces) {
                String action = piece.isInBase() ? "launch" : "move " + roll + " step(s)";
                System.out.println(piece.getNumber() + ". Piece " + piece.getNumber()
                        + " (" + board.describePosition(player, piece) + ", " + action + ")");
            }

            String input = scanner.nextLine().trim();
            quitIfRequested(input);

            try {
                int choice = Integer.parseInt(input);
                for (Piece piece : movablePieces) {
                    if (piece.getNumber() == choice) {
                        return piece;
                    }
                }
            } catch (NumberFormatException ignored) {
                // 不是数字，落到下面的提示重新输入
            }

            System.out.println("Please enter one of the listed piece numbers.");
        }
    }

    /** 等待玩家按回车（任意输入视为继续，q/quit 退出） */
    private void waitForEnter() {
        String input = scanner.nextLine().trim();
        quitIfRequested(input);
    }

    /** 用户输入 q 或 quit 时退出游戏 */
    private void quitIfRequested(String input) {
        if ("q".equalsIgnoreCase(input) || "quit".equalsIgnoreCase(input)) {
            System.out.println("Game ended.");
            System.exit(0);
        }
    }

    /** 回合轮转：按 0→1→2→3→0 循环 */
    private int nextPlayerIndex(int currentPlayerIndex) {
        return (currentPlayerIndex + 1) % players.size();
    }
}
