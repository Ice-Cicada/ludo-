package game;

import java.util.List;

public class Board {
    public static final int TRACK_LENGTH = 52;
    public static final int HOME_LENGTH = 6;
    public static final int FINISH_PROGRESS = TRACK_LENGTH + HOME_LENGTH;

    public boolean canMove(Piece piece, int diceValue) {
        if (piece.isFinished()) {
            return false;
        }
        if (piece.isInBase()) {
            return diceValue == 6;
        }
        return piece.getProgress() + diceValue <= FINISH_PROGRESS;
    }

    public MoveResult move(Player player, Piece piece, int diceValue, List<Player> players) {
        if (!canMove(piece, diceValue)) {
            throw new IllegalArgumentException("This piece cannot move with dice value " + diceValue);
        }

        if (piece.isInBase()) {
            piece.launch();
        } else {
            piece.advance(diceValue);
        }

        int captured = captureOpponents(player, piece, players);
        return new MoveResult(piece.isFinished(), captured);
    }

    public String describePosition(Player player, Piece piece) {
        if (piece.isInBase()) {
            return "base";
        }
        if (piece.isFinished()) {
            return "finished";
        }
        if (piece.isOnHomeLane()) {
            int homeStep = piece.getProgress() - TRACK_LENGTH + 1;
            return "home lane " + homeStep + "/" + HOME_LENGTH;
        }
        return "track square " + absoluteTrackPosition(player, piece);
    }

    private int captureOpponents(Player currentPlayer, Piece movedPiece, List<Player> players) {
        if (!movedPiece.isOnSharedTrack()) {
            return 0;
        }

        int square = absoluteTrackPosition(currentPlayer, movedPiece);
        int captured = 0;

        for (Player opponent : players) {
            if (opponent == currentPlayer) {
                continue;
            }

            for (Piece opponentPiece : opponent.getPieces()) {
                if (opponentPiece.isOnSharedTrack()
                        && absoluteTrackPosition(opponent, opponentPiece) == square) {
                    opponentPiece.sendToBase();
                    captured++;
                }
            }
        }

        return captured;
    }

    private int absoluteTrackPosition(Player player, Piece piece) {
        return (player.getStartOffset() + piece.getProgress()) % TRACK_LENGTH;
    }
}

