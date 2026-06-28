package game;

import java.util.List;

/**
 * 棋盘 —— 核心逻辑层，负责移动规则、踩子判定和位置描述。
 * 自身无可变状态，所有棋盘状态存储在 Piece.progress 中。
 * <p>
 * 核心概念：坐标系统
 * ===================
 * 棋盘 = 52 格环形公共轨道 + 每人 6 格家门跑道。
 * 位置用 Piece.progress（-1 ~ 58）表示"相对于该棋子起点的步数"。
 * <p>
 * 要判断两枚棋子是否在同一格（踩子），必须换算为绝对轨道位置：
 * absoluteTrackPosition = (Player.startOffset + Piece.progress) % 52
 * <p>
 * 每个玩家的起点在 52 格轨道上间隔 13 格：
 * 红 0  → 蓝 13 → 黄 26 → 绿 39
 *
 * @see Piece   —— 存储 progress 并暴露状态查询方法（isInBase, isOnSharedTrack 等）
 * @see Player  —— 提供 startOffset，用于绝对位置换算
 */
public class Board {
    /**
     * 公共环形轨道长度（格数）
     */
    public static final int TRACK_LENGTH = 52;
    /**
     * 家门跑道长度（格数），到达终点前最后的 6 步
     */
    public static final int HOME_LENGTH = 6;
    /**
     * 到达终点的 progress 值（= 52 + 6）。progress 等于此值时棋子为 finished 状态
     */
    public static final int FINISH_PROGRESS = TRACK_LENGTH + HOME_LENGTH;

    /**
     * 判断该棋子能否以当前骰子值移动。
     * <p>
     * 规则：
     * - 已到终点的棋子不能移动
     * - 在基地的棋子只有掷出 6 才能起飞
     * - 已出发的棋子前进后不能超过终点（必须正好到达）
     */
    public boolean canMove(Piece piece, int diceValue) {
        if (piece.isFinished()) {
            return false;                           // 已到终点，无需再动
        }
        if (piece.isInBase()) {
            return diceValue == 5 || diceValue == 6;                  // 在基地：只有 6 能起飞
        }
        // 在轨道或家门跑道上：前进后不能超出终点
        return piece.getProgress() + diceValue <= FINISH_PROGRESS;
    }

    /**
     * 执行移动：起飞 / 前进 → 踩子。
     *
     * @param player    当前玩家
     * @param piece     选中的棋子
     * @param diceValue 骰子点数（已通过 canMove 校验）
     * @param players   所有玩家列表（用于遍历对手做踩子判定）
     * @return MoveResult（是否刚到达终点、踩回了几枚对手棋子）
     */
    public MoveResult move(Player player, Piece piece, int diceValue, List<Player> players) {
        if (!canMove(piece, diceValue)) {
            throw new IllegalArgumentException("This piece cannot move with dice value " + diceValue);
        }

        // 1. 执行移动：在基地则起飞，否则前进
        if (piece.isInBase()) {
            piece.launch();             // progress: -1 → 0，棋子进入公共轨道
        } else {
            piece.advance(diceValue);   // progress += diceValue
        }

        // 2. 移动后检查是否踩到对手棋子（仅当棋子仍在公共轨道上时）
        int captured = captureOpponents(player, piece, players);
        return new MoveResult(piece.isFinished(), captured);
    }

    /**
     * 返回棋子位置的人类可读描述（供 Game 打印用）。
     */
    public String describePosition(Player player, Piece piece) {
        if (piece.isInBase()) {
            return "base";
        }
        if (piece.isFinished()) {
            return "finished";
        }
        if (piece.isOnHomeLane()) {
            int homeStep = piece.getProgress() - TRACK_LENGTH + 1;  // 1 ~ 6
            return "home lane " + homeStep + "/" + HOME_LENGTH;
        }
        return "track square " + absoluteTrackPosition(player, piece);  // 0 ~ 51
    }

    /**
     * 踩子判定：遍历所有对手，如果对手棋子也在公共轨道上且绝对位置相同，则将其踩回基地。
     * <p>
     * 注意：家门跑道（home lane）是各玩家独占的，不会发生踩子，所以先排除非公共轨道。
     * 返回被踩回的棋子数量（供 Game 打印战报）。
     */
    private int captureOpponents(Player currentPlayer, Piece movedPiece, List<Player> players) {
        if (!movedPiece.isOnSharedTrack()) {
            return 0;   // 在家门跑道或已到终点，不会踩到任何人
        }

        int square = absoluteTrackPosition(currentPlayer, movedPiece);
        int captured = 0;

        for (Player opponent : players) {
            if (opponent == currentPlayer) {
                continue;   // 跳过自己
            }

            for (Piece opponentPiece : opponent.getPieces()) {
                if (opponentPiece.isOnSharedTrack()
                        && absoluteTrackPosition(opponent, opponentPiece) == square) {
                    opponentPiece.sendToBase();      // 把对手棋子送回基地（progress → -1）
                    captured++;
                }
            }
        }

        return captured;
    }

    /**
     * 将 (玩家起点偏移 + 棋子前进步数) 换算为 52 格轨道上的绝对位置。
     * <p>
     * 这是理解整个棋盘的关键方法：
     * - progress 是相对于该棋子自己起点的步数
     * - 不同玩家的起点不同（startOffset: 红0 蓝13 黄26 绿39）
     * - 两个值相加再对 52 取模，得到在公共环上的绝对格子号
     * <p>
     * 例如：红方棋子 progress=5，蓝方棋子 progress=3
     * 红：absoluteTrackPosition = (0  + 5) % 52 = 5
     * 蓝：absoluteTrackPosition = (13 + 3) % 52 = 16
     * 两枚棋子不在同一格（5 ≠ 16），不会踩。
     * <p>
     * 又如：红方 progress=5，黄方 progress=0（刚起飞）
     * 红：absoluteTrackPosition = (0  + 5) % 52 = 5
     * 黄：absoluteTrackPosition = (26 + 0) % 52 = 26
     * 也不在同一格。
     */
    private int absoluteTrackPosition(Player player, Piece piece) {
        return (player.getStartOffset() + piece.getProgress()) % TRACK_LENGTH;
    }
}

