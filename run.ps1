$ErrorActionPreference = "Stop"

if (-not (Test-Path "out")) {
    New-Item -ItemType Directory -Path "out" | Out-Null
}

javac -encoding UTF-8 -d out src\Main.java src\game\*.java
java -cp out Main

