# Java Ludo Console MVP

This is a small console-first Ludo/Flying Chess project.

## Run

PowerShell:

```powershell
javac -encoding UTF-8 -d out src\Main.java src\game\*.java
java -cp out Main
```

Or use the helper script:

```powershell
.\run.ps1
```

## Current Rules

- Roll a 6 to launch a piece from base.
- Move one available piece each turn.
- Pieces must land exactly on the finish.
- Landing on an opponent on the shared track sends that opponent back to base.
- Rolling a 6 gives the same player another turn.

