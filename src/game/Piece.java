package game;

/**
 * 棋子 —— 核心状态机。
 *
 * 位置用一个整数 progress 表示，含义由 {@link Board} 来解释：
 *   -1          = 在基地（初始值，尚未出发）
 *    0 ..  51   = 在 52 格公共环形轨道上（从该玩家起点的前进步数）
 *   52 ..  57   = 在 6 格家门跑道上
 *   58          = 到达终点（= Board.FINISH_PROGRESS = TRACK_LENGTH + HOME_LENGTH）
 *
 * 注意：progress 是"相对于该玩家起点"的步数，不是棋盘上的绝对位置。
 * 绝对位置由 Board.absoluteTrackPosition() 结合 Player.startOffset 来换算。
 *
 * @see Board   —— 负责移动规则、踩子判定、位置换算
 * @see Player  —— 持有 startOffset（各颜色起点不同），拥有 4 枚棋子
 */
public class Piece {
    /** 棋子编号（1~4，同一玩家的四枚棋子以此区分） */
    private final int number;

    /** 位置进度：-1=基地, 0~51=公共轨道, 52~57=家门跑道, 58=终点 */
    private int progress = -1;

    public Piece(int number) {
        this.number = number;
    }

    public int getNumber() {
        return number;
    }

    public int getProgress() {
        return progress;
    }

    // ========== 以下四个方法是状态查询，供 Board.canMove() 和 Game 使用 ==========

    public boolean isInBase() {
        return progress < 0;                    // progress == -1
    }

    public boolean isOnSharedTrack() {
        return progress >= 0 && progress < Board.TRACK_LENGTH;   // 0 ~ 51
    }

    public boolean isOnHomeLane() {
        return progress >= Board.TRACK_LENGTH && progress < Board.FINISH_PROGRESS;  // 52 ~ 57
    }

    public boolean isFinished() {
        return progress == Board.FINISH_PROGRESS;  // 58
    }

    // ========== 以下三个方法是状态变更，由 Board.move() 调用 ==========

    /** 从基地飞到公共轨道起点（progress 0），仅在掷出 6 时由 Board 调用 */
    public void launch() {
        progress = 0;
    }

    /** 前进步数，由 Board.move() 在 canMove 检查通过后调用 */
    public void advance(int steps) {
        progress += steps;
    }

    /** 被对手踩回基地（progress 重置为 -1），由 Board.captureOpponents() 调用 */
    public void sendToBase() {
        progress = -1;
    }
}

