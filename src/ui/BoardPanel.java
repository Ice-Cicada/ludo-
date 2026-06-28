package ui;

import game.Board;
import game.Piece;
import game.Player;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import javax.imageio.ImageIO;

/**
 * 飞行棋棋盘渲染面板。
 *
 * <p>从服务器 STATE 或本地游戏动作接收状态更新，通过 {@link #updateState(List, int, Set)}
 * 同步后自动重绘。点击棋子时通过 {@link PieceClickListener} 回调通知宿主。
 * LudoFrame（本地）和 NetworkLudoFrame（网络）共享此类。</p>
 */
public class BoardPanel extends JPanel {

    /** 点击棋子时的回调。仅当点击了当前玩家的可移动棋子时触发。 */
    public interface PieceClickListener {
        void onPieceClicked(Piece piece);
    }

    private final Board board;
    private final PieceClickListener clickListener;
    private final Consumer<String> statusCallback;

    private List<Player> players = new ArrayList<>();
    private int currentPlayerIndex = 0;
    private Set<Piece> movablePieces = Set.of();

    private final List<PieceHitTarget> hitTargets = new ArrayList<>();
    private final List<Point> trackPoints = new ArrayList<>();
    private final BufferedImage boardImage = loadBoardImage();

    // ---- 棋盘布局数据（像素坐标 / 网格坐标） ----
    private static final int[][] TRACK_PIXELS = {
            {416, 1090}, {386, 1013}, {388, 945}, {418, 868}, {358, 818}, {285, 837},
            {214, 839}, {140, 814}, {113, 737}, {111, 669}, {111, 598}, {111, 526},
            {112, 458}, {139, 385}, {213, 357}, {285, 357}, {360, 384}, {418, 334},
            {388, 257}, {388, 190}, {419, 115}, {492, 80}, {558, 80}, {625, 80},
            {692, 80}, {757, 80}, {830, 114}, {863, 190}, {863, 257}, {831, 334},
            {887, 384}, {967, 357}, {1035, 356}, {1111, 385}, {1138, 457}, {1138, 527},
            {1138, 598}, {1139, 668}, {1138, 737}, {1111, 814}, {1035, 836}, {968, 837},
            {888, 815}, {833, 868}, {863, 946}, {863, 1014}, {835, 1090}, {759, 1115},
            {691, 1115}, {624, 1115}, {558, 1115}, {490, 1115}
    };

    private static final int[][] TRACK_GRID = {
            {6, 13}, {6, 12}, {6, 11}, {6, 10}, {6, 9}, {5, 8}, {4, 8}, {3, 8},
            {2, 8}, {1, 8}, {0, 8}, {0, 7}, {0, 6}, {1, 6}, {2, 6}, {3, 6},
            {4, 6}, {5, 6}, {6, 5}, {6, 4}, {6, 3}, {6, 2}, {6, 1}, {6, 0},
            {7, 0}, {8, 0}, {8, 1}, {8, 2}, {8, 3}, {8, 4}, {8, 5}, {9, 6},
            {10, 6}, {11, 6}, {12, 6}, {13, 6}, {14, 6}, {14, 7}, {14, 8},
            {13, 8}, {12, 8}, {11, 8}, {10, 8}, {9, 8}, {8, 9}, {8, 10},
            {8, 11}, {8, 12}, {8, 13}, {8, 14}, {7, 14}, {6, 14}
    };

    // ---- 构造 ----

    public BoardPanel(Board board, PieceClickListener clickListener, Consumer<String> statusCallback) {
        this.board = board;
        this.clickListener = clickListener;
        this.statusCallback = statusCallback;

        setBackground(new Color(229, 231, 235));
        setPreferredSize(new Dimension(660, 660));
        setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                handleClick(event.getPoint());
            }
        });
    }

    // ---- 公开 API ----

    /**
     * 更新棋盘状态并重绘。
     *
     * @param players            所有玩家列表
     * @param currentPlayerIndex 当前行动中的玩家索引
     * @param movablePieces      当前可移动的棋子集合（空集表示无可移动棋子或非选子阶段）
     */
    public void updateState(List<Player> players, int currentPlayerIndex, Set<Piece> movablePieces) {
        this.players = players;
        this.currentPlayerIndex = currentPlayerIndex;
        this.movablePieces = movablePieces;
        repaint();
    }

    /** 返回当前玩家（由 currentPlayerIndex 决定） */
    private Player currentPlayer() {
        if (players.isEmpty() || currentPlayerIndex >= players.size()) {
            return null;
        }
        return players.get(currentPlayerIndex);
    }

    /**
     * 根据 Player.startOffset 返回对应颜色。
     * 本地与网络客户端均可使用。
     */
    public static Color playerColor(Player player) {
        return switch (player.getStartOffset()) {
            case 1 -> new Color(37, 99, 235);
            case 14 -> new Color(22, 163, 74);
            case 27 -> new Color(220, 38, 38);
            case 40 -> new Color(234, 179, 8);
            default -> Color.DARK_GRAY;
        };
    }

    // ---- 鼠标点击 ----

    private void handleClick(Point clickPoint) {
        for (int i = hitTargets.size() - 1; i >= 0; i--) {
            PieceHitTarget target = hitTargets.get(i);
            if (target.shape().contains(clickPoint)) {
                Player cp = currentPlayer();
                if (cp == null) {
                    return;
                }
                if (target.player() != cp) {
                    statusCallback.accept("现在轮到 " + cp.getName() + "。");
                    return;
                }
                if (!movablePieces.contains(target.piece())) {
                    statusCallback.accept("这枚棋子本回合不能移动，请选择发光棋子。");
                    return;
                }
                clickListener.onPieceClicked(target.piece());
                return;
            }
        }
    }

    // ---- 渲染入口 ----

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        hitTargets.clear();
        trackPoints.clear();

        int boardSize = Math.min(getWidth(), getHeight()) - 56;
        int left = (getWidth() - boardSize) / 2;
        int top = (getHeight() - boardSize) / 2;
        int pieceSize = Math.max(20, (int) (boardSize * 0.034));

        drawBackground(g, left, top, boardSize);
        buildTrackPoints(left, top, boardSize);
        drawBoardImage(g, left, top, boardSize);
        drawPieces(g, left, top, boardSize, pieceSize);

        g.dispose();
    }

    // ---- 背景 ----

    private void drawBackground(Graphics2D g, int left, int top, int size) {
        g.setColor(new Color(229, 231, 235));
        g.fillRect(0, 0, getWidth(), getHeight());
    }

    // ---- 棋盘图片 ----

    private BufferedImage loadBoardImage() {
        File imageFile = new File("assets", "board.png");
        try {
            return ImageIO.read(imageFile);
        } catch (IOException exception) {
            System.err.println("Could not load board background: " + imageFile.getAbsolutePath());
            return null;
        }
    }

    private void drawBoardImage(Graphics2D g, int left, int top, int boardSize) {
        if (boardImage == null) {
            drawBoardGrid(g, left, top, boardSize / 15);
            return;
        }
        g.drawImage(boardImage, left, top, boardSize, boardSize, null);
        g.setColor(new Color(15, 23, 42));
        g.setStroke(new BasicStroke(2f));
        g.drawRect(left, top, boardSize, boardSize);
    }

    private void buildTrackPoints(int left, int top, int boardSize) {
        for (int[] pixel : TRACK_PIXELS) {
            trackPoints.add(imagePoint(left, top, boardSize, pixel[0], pixel[1]));
        }
    }

    // ---- 备用网格（无 board.png 时） ----

    private void drawBoardGrid(Graphics2D g, int left, int top, int cell) {
        g.setColor(Color.WHITE);
        g.fillRect(left, top, cell * 15, cell * 15);
        g.setColor(new Color(203, 213, 225));
        g.setStroke(new BasicStroke(0.8f));
        for (int i = 0; i <= 15; i++) {
            int pos = i * cell;
            g.drawLine(left + pos, top, left + pos, top + cell * 15);
            g.drawLine(left, top + pos, left + cell * 15, top + pos);
        }
        g.setColor(new Color(15, 23, 42));
        g.setStroke(new BasicStroke(2f));
        g.drawRect(left, top, cell * 15, cell * 15);
    }

    // ---- 棋子绘制 ----

    private void drawPieces(Graphics2D g, int left, int top, int boardSize, int pieceSize) {
        Map<String, Integer> stacked = new HashMap<>();
        for (Player player : players) {
            for (Piece piece : player.getPieces()) {
                Point basePoint = piecePoint(player, piece, left, top, boardSize);
                String key = basePoint.x + ":" + basePoint.y;
                int stackIdx = stacked.getOrDefault(key, 0);
                stacked.put(key, stackIdx + 1);

                Point point = offsetStack(basePoint, stackIdx, pieceSize);
                drawPiece(g, player, piece, point, pieceSize);
            }
        }
    }

    private void drawPiece(Graphics2D g, Player player, Piece piece, Point point, int pieceSize) {
        Player cp = currentPlayer();
        boolean movable = cp != null && player == cp && movablePieces.contains(piece);
        if (movable) {
            g.setColor(new Color(255, 214, 10));
            g.fillOval(point.x - pieceSize / 2 - 5, point.y - pieceSize / 2 - 5,
                    pieceSize + 10, pieceSize + 10);
        }

        Shape shape = new Ellipse2D.Double(point.x - pieceSize / 2.0, point.y - pieceSize / 2.0,
                pieceSize, pieceSize);
        g.setColor(playerColor(player));
        g.fill(shape);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(2f));
        g.draw(shape);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(12, pieceSize / 2)));
        drawCenteredText(g, String.valueOf(piece.getNumber()), point.x, point.y + 5, Color.WHITE);

        hitTargets.add(new PieceHitTarget(player, piece, shape));
    }

    // ---- 棋子屏幕坐标计算 ----

    private Point piecePoint(Player player, Piece piece, int left, int top, int boardSize) {
        if (piece.isInBase()) {
            int[] pixel = basePixel(player, piece.getNumber() - 1);
            return imagePoint(left, top, boardSize, pixel[0], pixel[1]);
        }
        if (piece.isFinished()) {
            return imagePoint(left, top, boardSize, 625, 599);
        }
        if (piece.isOnLaunchPad()) {
            int[] pixel = launchPadPixel(player);
            return imagePoint(left, top, boardSize, pixel[0], pixel[1]);
        }
        if (piece.isOnHomeLane()) {
            int homeIdx = piece.getProgress() - Board.TRACK_LENGTH - 1;
            int[] pixel = homeLanePixel(player, homeIdx);
            return imagePoint(left, top, boardSize, pixel[0], pixel[1]);
        }
        return trackPoints.get(board.absoluteTrackPosition(player, piece));
    }

    // ---- 各位置的像素坐标 ----

    private int[] basePixel(Player player, int index) {
        int[][] pixels = switch (player.getStartOffset()) {
            case 1 -> new int[][]{{116, 987}, {242, 987}, {116, 1106}, {242, 1106}};
            case 14 -> new int[][]{{116, 93}, {240, 93}, {116, 209}, {239, 209}};
            case 27 -> new int[][]{{1011, 93}, {1133, 93}, {1010, 209}, {1133, 209}};
            case 40 -> new int[][]{{1011, 988}, {1136, 987}, {1011, 1108}, {1136, 1107}};
            default -> new int[][]{{625, 599}, {625, 599}, {625, 599}, {625, 599}};
        };
        return pixels[index];
    }

    private int[] launchPadPixel(Player player) {
        return TRACK_PIXELS[player.getStartOffset()];
    }

    private int[] homeLanePixel(Player player, int index) {
        int[][] pixels = switch (player.getStartOffset()) {
            case 1 -> new int[][]{{625, 1021}, {625, 952}, {625, 884}, {625, 813}, {624, 741}, {625, 674}};
            case 14 -> new int[][]{{218, 599}, {286, 599}, {354, 598}, {419, 599}, {489, 599}, {555, 599}};
            case 27 -> new int[][]{{625, 196}, {625, 263}, {625, 334}, {625, 403}, {625, 470}, {625, 533}};
            case 40 -> new int[][]{{1034, 600}, {965, 600}, {896, 600}, {828, 600}, {761, 600}, {693, 601}};
            default -> new int[][]{{625, 599}, {625, 599}, {625, 599}, {625, 599}, {625, 599}, {625, 599}};
        };
        return pixels[Math.max(0, Math.min(index, pixels.length - 1))];
    }

    // ---- 坐标映射工具 ----

    private Point imagePoint(int left, int top, int boardSize, int imageX, int imageY) {
        int iw = boardImage == null ? 1254 : boardImage.getWidth();
        int ih = boardImage == null ? 1254 : boardImage.getHeight();
        int x = left + (int) Math.round(imageX * boardSize / (double) iw);
        int y = top + (int) Math.round(imageY * boardSize / (double) ih);
        return new Point(x, y);
    }

    private Point center(int left, int top, int cell, int gridX, int gridY) {
        return new Point(left + gridX * cell + cell / 2, top + gridY * cell + cell / 2);
    }

    private Rectangle cellRect(int left, int top, int cell, int gridX, int gridY, double insetRatio) {
        int inset = Math.max(2, (int) (cell * insetRatio));
        return new Rectangle(left + gridX * cell + inset, top + gridY * cell + inset,
                cell - inset * 2, cell - inset * 2);
    }

    private Point offsetStack(Point point, int stackIdx, int pieceSize) {
        int[][] offsets = {
                {0, 0},
                {-pieceSize / 3, -pieceSize / 3},
                {pieceSize / 3, -pieceSize / 3},
                {-pieceSize / 3, pieceSize / 3},
                {pieceSize / 3, pieceSize / 3}
        };
        int[] off = offsets[Math.min(stackIdx, offsets.length - 1)];
        return new Point(point.x + off[0], point.y + off[1]);
    }

    private void drawCenteredText(Graphics2D g, String text, int cx, int baselineY, Color color) {
        FontMetrics fm = g.getFontMetrics();
        int x = cx - fm.stringWidth(text) / 2;
        g.setColor(color);
        g.drawString(text, x, baselineY);
    }

    private Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    // ---- 数据记录 ----

    record PieceHitTarget(Player player, Piece piece, Shape shape) {}
}
