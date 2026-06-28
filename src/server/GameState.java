package server;

import game.Board;
import game.Dice;
import game.MoveResult;
import game.Piece;
import game.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 服务端游戏状态 —— 包装 Board/Dice/Players，管理网络回合流转。
 *
 * <p>所有公开方法均为 synchronized，因为多个 ClientHandler 线程可能并发调用。</p>
 *
 * <p>回合阶段：</p>
 * <ul>
 *   <li>{@link Phase#WAITING_FOR_ROLL} — 等待当前玩家发送 ROLL_DICE</li>
 *   <li>{@link Phase#WAITING_FOR_PIECE} — 等待当前玩家发送 MOVE_PIECE</li>
 *   <li>{@link Phase#GAME_OVER} — 游戏结束，不再接受任何操作</li>
 * </ul>
 */
public class GameState {

    public enum Phase {
        WAITING_FOR_ROLL,
        WAITING_FOR_PIECE,
        GAME_OVER
    }

    private final Board board = new Board();
    private final Dice dice = new Dice();
    private final List<Player> players = new ArrayList<>();

    private int currentPlayerIndex = 0;
    private int currentDice = 0;
    private Phase phase = Phase.WAITING_FOR_ROLL;
    private final Set<Integer> movablePieceNumbers = new HashSet<>();
    private int winnerId = -1;
    private String lastAction = "";

    public GameState() {
        players.add(new Player("蓝方", 1));
        players.add(new Player("绿方", 14));
        players.add(new Player("红方", 27));
        players.add(new Player("黄方", 40));
    }

    // ---- 公开操作 ----

    /**
     * 当前玩家掷骰子。
     *
     * @param playerId 发起请求的玩家（0-3）
     * @return 错误消息，成功时返回 null
     */
    public synchronized String rollDice(int playerId) {
        if (phase == Phase.GAME_OVER) {
            return "游戏已结束。";
        }
        if (playerId != currentPlayerIndex) {
            return "还没轮到你。";
        }
        if (phase != Phase.WAITING_FOR_ROLL) {
            return "请先选择一枚棋子。";
        }

        currentDice = dice.roll();
        Player player = players.get(playerId);

        movablePieceNumbers.clear();
        for (Piece piece : player.getPieces()) {
            if (board.canMove(piece, currentDice)) {
                movablePieceNumbers.add(piece.getNumber());
            }
        }

        lastAction = player.getName() + " 掷出 " + currentDice;

        if (movablePieceNumbers.isEmpty()) {
            lastAction += "，没有棋子可以移动";
            advanceTurn();
        } else {
            phase = Phase.WAITING_FOR_PIECE;
        }

        return null; // 成功
    }

    /**
     * 当前玩家选择棋子移动。
     *
     * @param playerId    发起请求的玩家（0-3）
     * @param pieceNumber 选择的棋子编号（1-4）
     * @return 错误消息，成功时返回 null
     */
    public synchronized String movePiece(int playerId, int pieceNumber) {
        if (phase == Phase.GAME_OVER) {
            return "游戏已结束。";
        }
        if (playerId != currentPlayerIndex) {
            return "还没轮到你。";
        }
        if (phase != Phase.WAITING_FOR_PIECE) {
            return "请先掷骰子。";
        }
        if (!movablePieceNumbers.contains(pieceNumber)) {
            return "这枚棋子不能移动。";
        }

        Player player = players.get(playerId);
        Piece piece = findPiece(player, pieceNumber);
        if (piece == null) {
            return "找不到该棋子。";
        }

        // 记录移动前的位置，用于 lastAction
        String fromPos = describePosition(player, piece);

        MoveResult result = board.move(player, piece, currentDice, players);
        String toPos = describePosition(player, piece);

        StringBuilder action = new StringBuilder();
        action.append(player.getName()).append(" 的 ")
              .append(pieceNumber).append(" 号棋子：")
              .append(fromPos).append(" → ").append(toPos);

        if (result.jumped()) {
            action.append(" ⤴跳子");
        }
        if (result.flew()) {
            action.append(" ✈飞棋");
        }
        if (result.capturedPieces() > 0) {
            action.append(" 踩回").append(result.capturedPieces()).append("子");
        }
        if (result.finished()) {
            action.append(" 到达终点");
        }
        lastAction = action.toString();

        // 检查胜利
        if (player.hasWon()) {
            phase = Phase.GAME_OVER;
            winnerId = playerId;
            return null;
        }

        // 回合管理
        if (currentDice == 6) {
            // 同一玩家再掷一次
            phase = Phase.WAITING_FOR_ROLL;
            currentDice = 0;
            movablePieceNumbers.clear();
        } else {
            advanceTurn();
        }

        return null; // 成功
    }

    // ---- JSON 构建（手动拼接，无外部依赖） ----

    /** 构建完整 STATE 消息的 JSON */
    public synchronized String buildStateJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"STATE\",");
        sb.append("\"currentPlayer\":").append(currentPlayerIndex).append(",");
        sb.append("\"dice\":").append(currentDice).append(",");
        sb.append("\"phase\":\"").append(phase.name()).append("\",");
        sb.append("\"pieces\":[");
        for (int i = 0; i < players.size(); i++) {
            sb.append("[");
            List<Piece> pieces = players.get(i).getPieces();
            for (int j = 0; j < pieces.size(); j++) {
                sb.append(pieces.get(j).getProgress());
                if (j < pieces.size() - 1) sb.append(",");
            }
            sb.append("]");
            if (i < players.size() - 1) sb.append(",");
        }
        sb.append("],");
        sb.append("\"movablePieces\":[");
        int idx = 0;
        for (int num : movablePieceNumbers) {
            sb.append(num);
            if (++idx < movablePieceNumbers.size()) sb.append(",");
        }
        sb.append("],");
        sb.append("\"lastAction\":\"").append(escapeJson(lastAction)).append("\"");
        sb.append("}");
        return sb.toString();
    }

    /** 构建 START_GAME 消息的 JSON */
    public String buildStartGameJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"START_GAME\",\"players\":[");
        for (int i = 0; i < players.size(); i++) {
            Player p = players.get(i);
            sb.append("{\"name\":\"").append(escapeJson(p.getName()))
              .append("\",\"offset\":").append(p.getStartOffset()).append("}");
            if (i < players.size() - 1) sb.append(",");
        }
        sb.append("]}");
        return sb.toString();
    }

    /** 构建 GAME_OVER 消息的 JSON */
    public String buildGameOverJson() {
        return "{\"type\":\"GAME_OVER\",\"winnerId\":" + winnerId
                + ",\"winnerName\":\"" + escapeJson(getWinnerName()) + "\"}";
    }

    /** 构建 PLAYER_LEFT 消息的 JSON */
    public String buildPlayerLeftJson(int playerId) {
        return "{\"type\":\"PLAYER_LEFT\",\"playerId\":" + playerId
                + ",\"playerName\":\"" + escapeJson(players.get(playerId).getName()) + "\"}";
    }

    // ---- 辅助方法 ----

    /** 返回当前可移动的棋子编号列表（不可修改），供机器人选子等用途 */
    public synchronized List<Integer> getMovablePieceNumbers() {
        return new ArrayList<>(movablePieceNumbers);
    }

    public int getCurrentPlayer() { return currentPlayerIndex; }
    public Phase getPhase() { return phase; }
    public int getWinnerId() { return winnerId; }
    public String getWinnerName() { return winnerId >= 0 ? players.get(winnerId).getName() : ""; }
    public List<Player> getPlayers() { return players; }
    public String getLastAction() { return lastAction; }

    private void advanceTurn() {
        currentPlayerIndex = (currentPlayerIndex + 1) % players.size();
        currentDice = 0;
        phase = Phase.WAITING_FOR_ROLL;
        movablePieceNumbers.clear();
    }

    private Piece findPiece(Player player, int number) {
        for (Piece p : player.getPieces()) {
            if (p.getNumber() == number) return p;
        }
        return null;
    }

    private String describePosition(Player player, Piece piece) {
        if (piece.isInBase()) return "基地";
        if (piece.isFinished()) return "终点";
        if (piece.isOnLaunchPad()) return "起飞台";
        if (piece.isOnHomeLane()) {
            int step = piece.getProgress() - Board.TRACK_LENGTH;
            return "家门跑道" + step + "/" + Board.HOME_LENGTH;
        }
        return "轨道" + board.absoluteTrackPosition(player, piece);
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
