package game;

import java.util.Random;

/**
 * 骰子 —— 简单的 1~6 随机数生成器。
 * 每次调用 roll() 独立随机，互不影响。
 *
 * 被 {@link Game#start()} 在每个回合开头调用。
 */
public class Dice {
    private final Random random = new Random();

    /** @return 1 到 6 之间的随机整数 */
    public int roll() {
        return random.nextInt(6) + 1;
    }
}

