package game;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class Game {
    private final Board board = new Board();
    private final Dice dice = new Dice();
    private final List<Player> players = new ArrayList<>();
    private final Scanner scanner = new Scanner(System.in);

    public Game() {
        players.add(new Player("Red", 0));
        players.add(new Player("Blue", 13));
        players.add(new Player("Yellow", 26));
        players.add(new Player("Green", 39));
    }

    public void start() {
        System.out.println("Flying Chess / Ludo Console MVP");
        System.out.println("Press Enter to roll. Type q at any prompt to quit.");

        int currentPlayerIndex = 0;
        while (true) {
            Player currentPlayer = players.get(currentPlayerIndex);

            printBoard();
            System.out.println();
            System.out.println(currentPlayer.getName() + "'s turn.");
            waitForEnter();

            int roll = dice.roll();
            System.out.println(currentPlayer.getName() + " rolled " + roll + ".");

            List<Piece> movablePieces = movablePieces(currentPlayer, roll);
            if (movablePieces.isEmpty()) {
                System.out.println("No available move.");
                currentPlayerIndex = nextPlayerIndex(currentPlayerIndex);
                continue;
            }

            Piece selectedPiece = choosePiece(currentPlayer, movablePieces, roll);
            MoveResult result = board.move(currentPlayer, selectedPiece, roll, players);
            System.out.println(currentPlayer.getName() + " moved piece " + selectedPiece.getNumber()
                    + " to " + board.describePosition(currentPlayer, selectedPiece) + ".");

            if (result.capturedPieces() > 0) {
                System.out.println("Captured " + result.capturedPieces() + " opponent piece(s).");
            }

            if (currentPlayer.hasWon()) {
                printBoard();
                System.out.println();
                System.out.println(currentPlayer.getName() + " wins!");
                return;
            }

            if (roll == 6) {
                System.out.println(currentPlayer.getName() + " rolled a 6 and gets another turn.");
            } else {
                currentPlayerIndex = nextPlayerIndex(currentPlayerIndex);
            }
        }
    }

    private void printBoard() {
        System.out.println();
        System.out.println("Current pieces:");
        for (Player player : players) {
            System.out.print(player.getName() + ": ");
            for (Piece piece : player.getPieces()) {
                System.out.print("#" + piece.getNumber() + "=" + board.describePosition(player, piece) + "  ");
            }
            System.out.println();
        }
    }

    private List<Piece> movablePieces(Player player, int roll) {
        List<Piece> movablePieces = new ArrayList<>();
        for (Piece piece : player.getPieces()) {
            if (board.canMove(piece, roll)) {
                movablePieces.add(piece);
            }
        }
        return movablePieces;
    }

    private Piece choosePiece(Player player, List<Piece> movablePieces, int roll) {
        while (true) {
            System.out.println("Choose a piece to move:");
            for (Piece piece : movablePieces) {
                String action = piece.isInBase() ? "launch" : "move " + roll + " step(s)";
                System.out.println(piece.getNumber() + ". Piece " + piece.getNumber()
                        + " (" + board.describePosition(player, piece) + ", " + action + ")");
            }

            String input = scanner.nextLine().trim();
            quitIfRequested(input);

            try {
                int choice = Integer.parseInt(input);
                for (Piece piece : movablePieces) {
                    if (piece.getNumber() == choice) {
                        return piece;
                    }
                }
            } catch (NumberFormatException ignored) {
                // Fall through to the retry message below.
            }

            System.out.println("Please enter one of the listed piece numbers.");
        }
    }

    private void waitForEnter() {
        String input = scanner.nextLine().trim();
        quitIfRequested(input);
    }

    private void quitIfRequested(String input) {
        if ("q".equalsIgnoreCase(input) || "quit".equalsIgnoreCase(input)) {
            System.out.println("Game ended.");
            System.exit(0);
        }
    }

    private int nextPlayerIndex(int currentPlayerIndex) {
        return (currentPlayerIndex + 1) % players.size();
    }
}

