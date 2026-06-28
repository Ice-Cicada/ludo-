# CLAUDE.md

本文件为 Claude Code (claude.ai/code) 在此仓库中工作时提供指引。

## 构建与运行

```powershell
javac -encoding UTF-8 -d out src\Main.java src\game\*.java
java -cp out Main
```

或使用辅助脚本：`.\run.ps1`（若 `out/` 目录不存在会自动创建）。

无 Maven/Gradle 等构建工具 — 直接用 `javac`。源码编码为 UTF-8。

## 架构

**入口：** `Main` 创建 `Game` 实例并调用 `start()`。

**棋盘坐标系统** — 棋盘由 52 格的环形公共轨道 + 每个玩家 6 格的家门跑道组成。棋子的位置用单个 `progress` 整数表示：

- `-1` = 在基地（尚未出发）
- `0..51` = 在公共轨道上，表示从该棋子自身起点的前进步数
- `52..57`（即 `TRACK_LENGTH` 到 `FINISH_PROGRESS`）= 在家门跑道上
- `58`（`FINISH_PROGRESS`）= 已到达终点

`Board.absoluteTrackPosition()` 将棋子的 progress + 其玩家的 startOffset 换算为公共轨道上的绝对位置（对 52 取模）。用于判定踩子 — 两枚棋子处于同一绝对位置即发生踩回。

**常量**（均在 `Board.java` 中）：`TRACK_LENGTH = 52`、`HOME_LENGTH = 6`、`FINISH_PROGRESS = 58`。

**回合流程**（`Game.start()`）：
1. 打印当前棋盘状态
2. 等待按回车（输入 `q` 退出）
3. 掷骰子
4. 通过 `Board.canMove()` 筛选可移动的棋子 — 掷到 6 才能起飞，不超出终点才能前进
5. 玩家输入编号选择棋子
6. `Board.move()` 执行：起飞或前进，然后踩回同一绝对位置上的对手棋子
7. 若玩家 4 枚棋子全部到达终点 → 获胜。若掷出 6 → 同一玩家继续。否则 → 轮到下一位玩家。

**核心类职责：**
- `Game` — 流程编排 + 输入输出；掌管主循环
- `Board` — 移动规则、踩子逻辑、位置描述；自身无可变状态
- `Player` — 名称、起点偏移（红/蓝/黄/绿 依次为 0/13/26/39）、持有 4 枚棋子
- `Piece` — 编号（1–4）和 `progress` 状态机
- `Dice` — 简单的 1–6 随机数
- `MoveResult` — Java record，包含 `finished`（布尔）和 `capturedPieces`（整数）
