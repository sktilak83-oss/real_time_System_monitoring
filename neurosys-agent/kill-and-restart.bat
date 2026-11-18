@echo off
cd /d "C:\Users\Karthik\OneDrive\Desktop\Real time system monitoring with AI prediction\neurosys-agent"
echo Killing old java agent...
taskkill /F /IM java.exe /T > nul 2>&1
ping -n 3 127.0.0.1 > nul
echo Starting fresh agent with new URL...
set "JAVA_EXE=C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot\bin\java.exe"
start "NeuroSys Agent" /MIN "%JAVA_EXE%" -jar "neurosys-agent-1.0.0-SNAPSHOT-exec.jar"
echo Done! Agent starting...
ping -n 4 127.0.0.1 > nul
