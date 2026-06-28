package game;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 棋盘 —— 核心逻辑层，负责移动规则、踩子判定和位置描述。
 * 自身无可变状态，所有棋盘状态存储在 Piece.progress 中。
 * <p>
 * 核心概念：坐标系统
 * ===================
 * 棋盘 = 起飞台 + 52 格环形公共轨道 + 每人 6 格家门跑道。
 * 位置用 Piece.progress（-1 ~ 59）表示"相对于该棋子起点的步数"。
 * <p>
 * 要判断两枚棋子是否在同一格（踩子），必须换算为绝对轨道位置：
 * absoluteTrackPosition = (Player.startOffset + Piece.progress) % 52
 * <p>
 * 每个玩家的起点在 52 格轨道上间隔 13 格，起点是三角块旁边的同色格：
 * 蓝 1 → 绿 14 → 红 27 → 黄 40
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
     * 到达终点的 progress 值（起飞台 0 + 52 格公共轨道 + 6 格家门跑道 + 终点）
     */
    public static final int FINISH_PROGRESS = TRACK_LENGTH + HOME_LENGTH + 1;

    /**
     * 跳子距离：棋子落在自己同色格时自动前进的步数。
     * 由于同色格每 4 格出现一次，跳 4 格正好到下一个同色格。
     */
    private static final int JUMP_DISTANCE = 4;

    /**
     * 飞棋配对：棋盘上两组对称的飞点，落在起点则飞向终点（仅向前触发）。
     * 52 格轨道上 1/4 与 3/4 处一组，2/4 与 0/4 处一组。
     */
    private static final Map<Integer, Integer> FLY_FROM_TO = Map.of(
            13, 39,   // 1/4 组
            26, 0     // 2/4 组
    );

    /** 双向飞点映射（from→to 和 to→from 都可用，由 move() 中的方向检查决定是否触发） */
    private static final Map<Integer, Integer> FLY_MAP = buildFlyMap();

    private static Map<Integer, Integer> buildFlyMap() {
        Map<Integer, Integer> map = new HashMap<>(FLY_FROM_TO);
        FLY_FROM_TO.forEach((from, to) -> map.put(to, from));
        return Collections.unmodifiableMap(map);
    }

    /**
     * 判断该棋子能否以当前骰子值移动。
     * <p>
     * 规则：
     * - 已到终点的棋子不能移动
     * - 在基地的棋子只有掷出 5 或 6 才能飞到起飞台
     * - 已出发的棋子前进后不能超过终点（必须正好到达）
     */
    public boolean canMove(Piece piece, int diceValue) {
        if (piece.isFinished()) {
            return false;                           // 已到终点，无需再动
        }
        if (piece.isInBase()) {
            return diceValue == 5 || diceValue == 6;  // 在基地：5 或 6 能起飞
        }
        // 在轨道或家门跑道上：前进后不能超出终点
        return piece.getProgress() + diceValue <= FINISH_PROGRESS;
    }

    /**
     * 执行移动：起飞 / 前进 → 踩子 → 跳子 → 踩子 → 飞棋 → 踩子。
     *
     * <p>跳子规则：棋子落在自己的同色格（每 4 格一个，分布在 52 格轨道上）时，
     * 自动再前进 {@value #JUMP_DISTANCE} 步。跳子不递归 —— 跳后不再检查跳子。</p>
     *
     * <p>飞棋规则：棋子落在飞点（绝对位置 13↔39、26↔0）时，
     * 飞向配对位置。仅当配对位置在棋子自身 progress 坐标中为"向前"时才触发。
     * 飞棋不递归 —— 飞后不再检查飞棋。</p>
     *
     * @param player    当前玩家
     * @param piece     选中的棋子
     * @param diceValue 骰子点数（已通过 canMove 校验）
     * @param players   所有玩家列表（用于遍历对手做踩子判定）
     * @return MoveResult（是否终点、累计踩回数、是否跳子、是否飞棋）
     */
    public MoveResult move(Player player, Piece piece, int diceValue, List<Player> players) {
        if (!canMove(piece, diceValue)) {
            throw new IllegalArgumentException("This piece cannot move with dice value " + diceValue);
        }

        boolean jumped = false;
        boolean flew = false;

        // 1. 执行移动：在基地则起飞到起飞台，否则前进
        if (piece.isInBase()) {
            piece.launch();             // progress: -1 → 0，棋子进入起飞台
        } else {
            piece.advance(diceValue);   // progress += diceValue
        }

        // 2. 移动后检查是否踩到对手棋子（仅当棋子仍在公共轨道上时）
        int captured = captureOpponents(player, piece, players);

        // 3. 跳子：落在公共轨道上的同色格 → 自动前进 4 步（不递归）
        if (canJump(player, piece)) {
            piece.advance(JUMP_DISTANCE);
            jumped = true;
            captured += captureOpponents(player, piece, players);
        }

        // 4. 飞棋：落在公共轨道上的飞点 → 飞向配对位置（仅向前，不递归）
        if (!jumped && piece.isOnSharedTrack()) {
            int flySteps = getFlySteps(player, piece);
            if (flySteps > 0) {
                piece.advance(flySteps);
                flew = true;
                captured += captureOpponents(player, piece, players);
            }
        }

        return new MoveResult(piece.isFinished(), captured, jumped, flew);
    }

    /**
     * 检查棋子是否可以跳子：必须在公共轨道上、落在自己同色格、跳后不超出终点。
     */
    private boolean canJump(Player player, Piece piece) {
        return piece.isOnSharedTrack()
                && isOwnColorSquare(player, piece)
                && piece.getProgress() + JUMP_DISTANCE <= FINISH_PROGRESS;
    }

    /**
     * 判断棋子当前所在的绝对位置是否为该玩家的同色格。
     * 同色格定义：{@code absolutePosition % 4 == startOffset % 4}，
     * 即 52 格轨道上每 4 格出现一个同色格（每种颜色 13 个）。
     */
    private boolean isOwnColorSquare(Player player, Piece piece) {
        if (!piece.isOnSharedTrack()) {
            return false;
        }
        int absPos = absoluteTrackPosition(player, piece);
        return absPos % 4 == player.getStartOffset() % 4;
    }

    /**
     * 计算飞棋需要前进的步数。
     *
     * <p>当前绝对位置在飞点映射中 → 计算配对位置对应的目标 progress。
     * 仅当目标 progress &gt; 当前 progress（向前飞行）且不超过终点时返回正数，
     * 否则返回 0（不飞）。</p>
     *
     * @return 飞棋前进步数，0 表示不触发飞棋
     */
    private int getFlySteps(Player player, Piece piece) {
        int absPos = absoluteTrackPosition(player, piece);
        Integer flyDest = FLY_MAP.get(absPos);
        if (flyDest == null) {
            return 0;
        }

        // 将配对绝对位置换算为该玩家坐标系下的 progress
        int targetProgress = (flyDest - player.getStartOffset() + TRACK_LENGTH) % TRACK_LENGTH;
        // 飞点映射给出的目标始终在公共轨道范围内，若刚好算得 0 则落在起飞台（不触发）
        if (targetProgress == 0) {
            return 0;
        }

        int currentProgress = piece.getProgress();
        int steps = targetProgress - currentProgress;
        if (steps <= 0 || currentProgress + steps > FINISH_PROGRESS) {
            return 0;   // 向后飞或超出终点则不触发
        }
        return steps;
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
        if (piece.isOnLaunchPad()) {
            return "launch pad";
        }
        if (piece.isOnHomeLane()) {
            int homeStep = piece.getProgress() - TRACK_LENGTH;  // 1 ~ 6
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
     * - 不同玩家的起点不同（startOffset: 蓝1 绿14 红27 黄40）
     * - 两个值相加再对 52 取模，得到在公共环上的绝对格子号
     * <p>
     * 例如：红方棋子 progress=5，蓝方棋子 progress=3
     * 红：absoluteTrackPosition = (27 + 5) % 52 = 32
     * 蓝：absoluteTrackPosition = (1  + 3) % 52 = 4
     * 两枚棋子不在同一格（32 ≠ 4），不会踩。
     * <p>
     * progress=0 表示起飞台，不属于公共轨道，不参与踩子。
     */
    public int absoluteTrackPosition(Player player, Piece piece) {
        return (player.getStartOffset() + piece.getProgress()) % TRACK_LENGTH;
    }
}
