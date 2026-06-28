package game;

public class Piece {
    private final int number;
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

    public boolean isInBase() {
        return progress < 0;
    }

    public boolean isOnSharedTrack() {
        return progress >= 0 && progress < Board.TRACK_LENGTH;
    }

    public boolean isOnHomeLane() {
        return progress >= Board.TRACK_LENGTH && progress < Board.FINISH_PROGRESS;
    }

    public boolean isFinished() {
        return progress == Board.FINISH_PROGRESS;
    }

    public void launch() {
        progress = 0;
    }

    public void advance(int steps) {
        progress += steps;
    }

    public void sendToBase() {
        progress = -1;
    }
}

