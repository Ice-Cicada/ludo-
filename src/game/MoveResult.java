package game;

/**
 * 移动结果 —— 由 {@link Board#move} 返回，供 {@link Game#start} 消费。
 *
 * @param finished        该棋子此次移动后是否刚好到达终点
 * @param capturedPieces  踩回基地的对手棋子数量
 */
public record MoveResult(
        boolean finished,
        int capturedPieces) {
}

