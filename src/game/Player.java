package game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Player {
    private final String name;
    private final int startOffset;
    private final List<Piece> pieces = new ArrayList<>();

    public Player(String name, int startOffset) {
        this.name = name;
        this.startOffset = startOffset;
        for (int i = 1; i <= 4; i++) {
            pieces.add(new Piece(i));
        }
    }

    public String getName() {
        return name;
    }

    public int getStartOffset() {
        return startOffset;
    }

    public List<Piece> getPieces() {
        return Collections.unmodifiableList(pieces);
    }

    public boolean hasWon() {
        for (Piece piece : pieces) {
            if (!piece.isFinished()) {
                return false;
            }
        }
        return true;
    }
}

