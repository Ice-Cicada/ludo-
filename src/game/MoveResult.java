package game;

/**
 * 移动结果 —— 由 {@link Board#move} 返回，供 Game 和 LudoFrame 消费。
 *
 * @param finished        该棋子此次移动后是否刚好到达终点
 * @param capturedPieces  踩回基地的对手棋子数量（包含移动、跳子、飞棋各阶段的累计）
 * @param jumped          此次移动是否触发了跳子（同色格自动前进 4 步）
 * @param flew            此次移动是否触发了飞棋（飞点配对传送）
 */
public record MoveResult(
        boolean finished,
        int capturedPieces,
        boolean jumped,
        boolean flew) {
    /** 无跳子/飞棋时的便捷构造（向后兼容） */
    public MoveResult(boolean finished, int capturedPieces) {
        this(finished, capturedPieces, false, false);
    }
}

