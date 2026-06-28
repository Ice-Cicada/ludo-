# Java Ludo / Flying Chess

This is a small Java Ludo/Flying Chess project with a Swing GUI.
The board is rendered from `assets/board.png`; Java draws the moving pieces on top.

## Run

### Local (hot-seat)
```powershell
.\run.ps1
```

### Network Server
```powershell
javac -encoding UTF-8 -d out src\Main.java src\game\*.java src\ui\*.java src\server\*.java src\network\*.java
java -cp out server.GameServer
```

### Network Client (×4)
```powershell
java -cp out ui.NetworkLudoFrame
```

## Current Rules

- Roll a 5 or 6 to move a piece from base to its launch pad.
- Move one available piece each turn.
- Pieces must land exactly on the finish.
- Landing on an opponent on the shared track sends that opponent back to base.
- Rolling a 6 gives the same player another turn.
- **Jump（跳子）**: Landing on your own color square auto-advances 4 steps.
- **Fly（飞棋）**: Landing on a fly-point square (13↔39, 26↔0) teleports to the paired square.
