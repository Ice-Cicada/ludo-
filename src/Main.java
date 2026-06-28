import game.Game;

/**
 * 入口类。
 * 只做一件事：创建 Game 并启动。所有逻辑都在 Game 及其下属类中。
 * 编译时注意用 -encoding UTF-8，否则中文可能乱码。
 */
public class Main {
    public static void main(String[] args) {
        Game game = new Game();
        game.start();
    }
}

