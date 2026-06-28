# CLAUDE.md

本文件为 Claude Code (claude.ai/code) 在此仓库中工作时提供指引。

## 构建与运行

### 本地游戏
```powershell
javac -encoding UTF-8 -d out src\Main.java src\game\*.java src\ui\*.java src\server\*.java src\network\*.java
java -cp out Main
```

或使用辅助脚本：`.\run.ps1`（若 `out/` 目录不存在会自动创建）。

### 联机服务器
```powershell
java -cp out server.GameServer
# 可选指定端口：java -cp out server.GameServer 9877
```

### 联机客户端
```powershell
java -cp out ui.NetworkLudoFrame
# 可选指定地址端口：java -cp out ui.NetworkLudoFrame 192.168.1.100 9876
```

无 Maven/Gradle 等构建工具 — 直接用 `javac`。源码编码为 UTF-8。联机模式无第三方依赖，JSON 协议手动构建。

## 架构

**入口：** `Main` 启动 Swing GUI（`LudoFrame`），而非控制台循环。原有的 `Game.start()` 控制台回合循环仍然保留在 `game` 包中，但不作为默认入口运行。

**两套交互层，同一规则引擎：**
- `ui.LudoFrame` — 图形界面（默认），渲染棋盘、处理鼠标点击选子、包含掷骰按钮
- `Game.start()` — 控制台回合循环（通过按回车 + 输入选子），仅供调试或纯文字对战时手动调用

两者都依赖 `Board`（规则引擎），`Board` 自身无状态。

**棋盘坐标系统** — 棋盘由 52 格的环形公共轨道 + 每个玩家 6 格的家门跑道组成。棋子的位置用单个 `progress` 整数表示（定义在 `Piece.java`）：

- `-1` = 在基地（尚未出发）
- `0` = 在起飞台（刚掷出 5/6 飞出的瞬间位置，不参与踩子判定）
- `1..52` = 在公共轨道上（`TRACK_LENGTH = 52`），表示从该棋子自身起点的前进步数
- `53..58`（即 `TRACK_LENGTH+1` 到 `FINISH_PROGRESS-1`）= 在家门跑道上（`HOME_LENGTH = 6`）
- `59`（`FINISH_PROGRESS = TRACK_LENGTH + HOME_LENGTH + 1`）= 已到达终点

`Board.absoluteTrackPosition()` 将棋子的 progress + 其玩家的 startOffset 换算为公共轨道上的绝对位置（对 52 取模）。仅当棋子 `isOnSharedTrack()`（progress 在 1~52）时才参与踩子判定 — 两枚棋子处于同一绝对位置即被踩回。

**常量**（均在 `Board.java` 中）：`TRACK_LENGTH = 52`、`HOME_LENGTH = 6`、`FINISH_PROGRESS = 59`、`JUMP_DISTANCE = 4`。

**跳子（Jump / 同色跳）**：52 格公共轨道上每种颜色有 13 个"同色格"（每 4 格一个，由 `absolutePosition % 4 == startOffset % 4` 判定）。棋子落在自己同色格时，自动再前进 4 步。跳子在正常移动后的踩子检查之后触发，触发后再次检查踩子。跳子不递归（跳后不再检查跳子）。

**飞棋（Fly / 飞点传送）**：棋盘上有两组对称的飞点——绝对位置 13↔39 和 26↔0。棋子落在飞点时，飞向配对位置。仅当配对位置在棋子自身 progress 坐标系中为"向前"时触发，否则跳过。飞棋在跳子之后触发（若已跳子则不飞），触发后再次检查踩子。飞棋不递归。

**移动完整流程**（`Board.move()`）：1. 正常移动 → 2. 踩子 → 3. 跳子（若满足条件）→ 4. 踩子 → 5. 飞棋（若满足条件且未跳子）→ 6. 踩子。

**控制台流程**（`Game.start()`）：
1. 打印当前棋盘状态
2. 等待按回车（输入 `q` 退出）
3. 掷骰子
4. 通过 `Board.canMove()` 筛选可移动的棋子 — 掷到 5 或 6 才能起飞，不超出终点才能前进
5. 玩家输入编号选择棋子
6. `Board.move()` 执行：起飞或前进，然后踩回同一绝对位置上的对手棋子
7. 若玩家 4 枚棋子全部到达终点 → 获胜。若掷出 6 → 同一玩家继续。否则 → 轮到下一位玩家。

**GUI 流程**（`LudoFrame`）：点击"掷骰子"按钮 → 自动掷骰、筛选可移动棋子（发光显示）→ 点击发光棋子执行移动 → 掷 6 连掷，否则自动切换玩家。棋盘使用 `assets/board.png` 作为背景，在上面绘制棋子。

**核心类职责：**
- `Main` — 入口，仅启动 `LudoFrame`
- `LudoFrame` — Swing GUI，拥有独立的 UI 回合状态（`waitingForPiece`、`movablePieces` 等），通过 `board.move()` 调用规则引擎
- `Game` — 控制台流程编排 + 输入输出；掌管主循环（代码仍在，非默认入口）
- `Board` — 移动规则、踩子逻辑、位置描述；自身无可变状态
- `Player` — 名称、起点偏移（蓝 1 / 绿 14 / 红 27 / 黄 40）、持有 4 枚棋子
- `Piece` — 编号（1–4）和 `progress` 状态机（`isInBase`、`isOnLaunchPad`、`isOnSharedTrack`、`isOnHomeLane`、`isFinished`）
- `Dice` — 简单的 1–6 随机数
- `MoveResult` — Java record，包含 `finished`（是否终点）、`capturedPieces`（累计踩回数）、`jumped`（是否跳子）、`flew`（是否飞棋）

## 联机架构

**拓扑：** 一个 GameServer + 一个 GameState + 最多 4 个 NetworkLudoFrame 客户端。无房间系统，先到先得。

**协议：** 纯文本 JSON，一行一条，TCP Socket。客户端发送 `ROLL_DICE` / `MOVE_PIECE`，服务器广播 `STATE`（完整棋子状态 + 回合信息）。

**服务端（`src/server/`）：**
- `GameServer` — TCP accept 循环，分配 playerId（0-3），满 4 人后设 `gameStarted=true` 并广播 START_GAME。入口含 `main()`，默认端口 9876。
- `ClientHandler` — 每个客户端一个读取线程，`BufferedReader.readLine()` → `GameServer.handleAction()`。
- `GameState` — 包装现有 Board/Dice/Players，管理回合 Phase 枚举（`WAITING_FOR_ROLL` / `WAITING_FOR_PIECE` / `GAME_OVER`）。支持掷 6 连掷、无子自动跳回合。所有公开方法为 `synchronized`。

**客户端（`src/ui/` + `src/network/`）：**
- `GameClient` — TCP 客户端连接器，后台 reader 线程将每行 JSON 回调给 NetworkLudoFrame。
- `NetworkLudoFrame` — Swing 窗口，与 LudoFrame 结构相似但由服务端 STATE 驱动。维护本地 Player/Piece 镜像（通过 `Piece.setProgress()` 同步）。`BoardPanel` 渲染类与本地游戏共享。
- 入口在 `NetworkLudoFrame.main()`。

**关键设计点：**
- `Piece.setProgress(int)` — 专供客户端从服务端 STATE 同步 progress，服务端绝不调用。
- `BoardPanel` — 提取为独立类，供 LudoFrame（本地）和 NetworkLudoFrame（网络）共享。
- 服务端权威：所有规则判定在服务端完成，客户端只渲染状态和发送操作。
