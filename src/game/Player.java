package game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 玩家 —— 持有 4 枚棋子和一个起点偏移。
 *
 * startOffset 是核心概念：Ludo/Flying Chess 的 52 格轨道被四个玩家均分，
 * 每个玩家的"起点"在轨道上的绝对位置不同：
 *   蓝=1, 绿=14, 红=27, 黄=40
 *
 * 这个偏移被 {@link Board#absoluteTrackPosition(Player, Piece)} 使用：
 *   absoluteTrackPosition = (startOffset + piece.progress) % 52
 *
 * 这就是为什么同一段 progress 数值对不同玩家意味着不同的绝对轨道位置。
 *
 * @see Piece  —— 每枚棋子的 progress 状态
 * @see Board  —— 使用 startOffset 做位置换算和踩子判定
 */
public class Player {
    private final String name;
    /** 该玩家起点在 52 格公共轨道上的绝对位置（蓝1 绿14 红27 黄40） */
    private final int startOffset;
    private final List<Piece> pieces = new ArrayList<>();

    public Player(String name, int startOffset) {
        this.name = name;
        this.startOffset = startOffset;
        for (int i = 1; i <= 4; i++) {
            pieces.add(new Piece(i));   // 每玩家 4 枚棋子，编号 1~4
        }
    }

    public String getName() {
        return name;
    }

    public int getStartOffset() {
        return startOffset;
    }

    /** 返回不可修改的棋子列表，仅供遍历查看 */
    public List<Piece> getPieces() {
        return Collections.unmodifiableList(pieces);
    }

    /** 胜利条件：4 枚棋子全部到达终点（progress == FINISH_PROGRESS） */
    public boolean hasWon() {
        for (Piece piece : pieces) {
            if (!piece.isFinished()) {
                return false;
            }
        }
        return true;
    }
}
