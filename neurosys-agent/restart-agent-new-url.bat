@echo off
title NeuroSys Agent - New URL Restart
cd /d "%~dp0"

echo Stopping any running agent...
taskkill /F /IM java.exe /FI "WINDOWTITLE eq NeuroSys Agent" > nul 2>&1
ping -n 2 127.0.0.1 > nul

echo Starting agent with new Railway URL...
set "JAVA_EXE=C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot\bin\java.exe"
if not exist "%JAVA_EXE%" set "JAVA_EXE=java.exe"

start "NeuroSys Agent" /MIN "%JAVA_EXE%" ^
  -Dneurosys.server.url=https://realtimesystemmonitoring-production-ca12724a.up.railway.app/api/v1 ^
  -jar "%~dp0neurosys-agent-1.0.0-SNAPSHOT-exec.jar"

echo Agent started! Check logs/neurosys-agent.log for connection status.
ping -n 3 127.0.0.1 > nul
