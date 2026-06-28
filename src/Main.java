import ui.LudoFrame;

import javax.swing.SwingUtilities;

/**
 * 入口类。
 * 默认启动 Swing 图形版。核心规则仍然放在 game 包里。
 * 编译时注意用 -encoding UTF-8，否则中文可能乱码。
 */
public class Main {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            LudoFrame frame = new LudoFrame();
            frame.setVisible(true);
        });
    }
}
