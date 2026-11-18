package com.neurosys.agent.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurosys.agent.config.AgentConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class PowerCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(PowerCommandHandler.class);
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public PowerCommandHandler() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public void pollAndExecutePendingCommand() {
        try {
            long startTime = System.currentTimeMillis();
            String agentId = AgentConfig.getAgentId();
            if (agentId == null || agentId.isBlank()) return;

            String url = AgentConfig.getServerUrl() + "/agent/power-commands/pending?agentId=" + agentId;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                String body = response.body().trim();
                if (body.startsWith("{")) {
                    Map<String, Object> respMap = objectMapper.readValue(body, Map.class);
                    if (respMap.containsKey("data") && respMap.get("data") != null) {
                        Map<String, Object> cmdData = (Map<String, Object>) respMap.get("data");
                        String commandId = (String) cmdData.get("id");
                        String commandType = (String) cmdData.get("commandType");

                        if (commandId != null && commandType != null) {
                            long elapsedMs = System.currentTimeMillis() - startTime;
                            log.info("[PERF LOG] [AGENT] Received command {} ({}) from backend in {}ms at {}", 
                                    commandId, commandType, elapsedMs, Instant.now());
                            executeCommand(commandId, commandType);
                        }
                    }
                }
            } else if (response.statusCode() != 404) {
                log.debug("Power command polling status: HTTP {}", response.statusCode());
            }
        } catch (Exception e) {
            log.debug("Power command polling error: {}", e.getMessage());
        }
    }

    private void executeCommand(String commandId, String commandType) {
        // Step 1: Report EXECUTING status asynchronously (non-blocking)
        updateStatusAsync(commandId, "EXECUTING", null);

        try {
            String os = System.getProperty("os.name").toLowerCase();
            boolean isWindows = os.contains("win");
            long startExecMs = System.currentTimeMillis();

            // Use absolute system32 path so commands work reliably in all contexts
            String winRoot = System.getenv("SystemRoot");
            if (winRoot == null || winRoot.isEmpty()) winRoot = "C:\\Windows";
            String sys32 = winRoot + "\\System32";

            switch (commandType.toUpperCase()) {
                case "LOCK":
                    log.info("[PERF LOG] [AGENT] Executing instant LOCK workstation at {}", Instant.now());
                    if (isWindows) {
                        // The agent may run as SYSTEM in Session 0 (non-interactive).
                        // LockWorkStation() only works in an interactive user session.
                        // Strategy: try multiple methods from strongest to most compatible.

                        // Method 1: PowerShell with Register-ScheduledTask targeting the current logged-in user's session
                        try {
                            // Write a tiny lock bat to TEMP then run it as the logged-in user via scheduled task
                            String tempDir = System.getenv("TEMP");
                            if (tempDir == null || tempDir.isEmpty()) tempDir = "C:\\Windows\\Temp";
                            java.io.File lockBat = new java.io.File(tempDir, "ns_lock_now.bat");
                            java.nio.file.Files.writeString(lockBat.toPath(),
                                "@echo off\r\n" +
                                sys32 + "\\rundll32.exe user32.dll,LockWorkStation\r\n");

                            String taskName = "NsLockSession";
                            // Build PowerShell that finds the active session user, registers a task as that user, runs it immediately
                            String psLock =
                                "$loggedUser = (Get-WmiObject -Class Win32_ComputerSystem).UserName;" +
                                "if (!$loggedUser) { $sessions = query session 2>&1; $activeLine = $sessions | Where-Object { $_ -match 'Active' } | Select-Object -First 1; if ($activeLine -match '\\s+(\\S+)\\s+.*Active') { $loggedUser = $Matches[1] } };" +
                                "$batPath = '" + lockBat.getAbsolutePath().replace("\\", "\\\\") + "';" +
                                "$taskName = '" + taskName + "';" +
                                "$action = New-ScheduledTaskAction -Execute 'cmd.exe' -Argument ('/c \"' + $batPath + '\"');" +
                                "$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries;" +
                                "if ($loggedUser) {" +
                                "  $domainUser = $loggedUser;" +
                                "  Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue | Out-Null;" +
                                "  Register-ScheduledTask -Force -TaskName $taskName -Action $action -Settings $settings -RunLevel Limited -User $domainUser | Out-Null;" +
                                "} else {" +
                                "  Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue | Out-Null;" +
                                "  Register-ScheduledTask -Force -TaskName $taskName -Action $action -Settings $settings | Out-Null;" +
                                "};" +
                                "Start-ScheduledTask -TaskName $taskName;" +
                                "Start-Sleep -Seconds 3;" +
                                "Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue | Out-Null;";

                            Process psProc = new ProcessBuilder(
                                "powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", psLock
                            ).redirectErrorStream(true).start();
                            String psOut = new String(psProc.getInputStream().readAllBytes());
                            psProc.waitFor(10, TimeUnit.SECONDS);
                            log.info("[AGENT] LOCK via PS ScheduledTask as logged-in user. Output: {}", psOut.trim());
                        } catch (Exception e) {
                            log.warn("[AGENT] PS ScheduledTask LOCK error: {}", e.getMessage());
                        }

                        // Method 2: Fallback - schtasks CLI with INTERACTIVE flag
                        try {
                            String taskName2 = "NsLockInteractive";
                            String[] createArgs = {
                                sys32 + "\\schtasks.exe", "/create", "/f",
                                "/tn", taskName2,
                                "/tr", "\"" + sys32 + "\\rundll32.exe\" user32.dll,LockWorkStation",
                                "/sc", "once", "/st", "00:00",
                                "/ru", "INTERACTIVE"
                            };
                            Process create2 = new ProcessBuilder(createArgs).redirectErrorStream(true).start();
                            create2.waitFor(3, TimeUnit.SECONDS);
                            Process run2 = new ProcessBuilder(sys32 + "\\schtasks.exe", "/run", "/tn", taskName2)
                                .redirectErrorStream(true).start();
                            run2.waitFor(2, TimeUnit.SECONDS);
                            new Thread(() -> {
                                try { Thread.sleep(5000);
                                    new ProcessBuilder(sys32 + "\\schtasks.exe", "/delete", "/f", "/tn", taskName2).start();
                                } catch (Exception ignored2) {}
                            }).start();
                            log.info("[AGENT] LOCK via schtasks /ru INTERACTIVE fallback launched");
                        } catch (Exception e2) {
                            log.warn("[AGENT] schtasks INTERACTIVE LOCK error: {}", e2.getMessage());
                        }

                        // Method 3: Direct JNA (works if agent IS in an interactive session)
                        try {
                            com.sun.jna.platform.win32.User32.INSTANCE.LockWorkStation();
                            log.info("[AGENT] LOCK via JNA LockWorkStation called");
                        } catch (Throwable t) {
                            log.warn("[AGENT] JNA LockWorkStation: {}", t.getMessage());
                        }
                    } else {
                        try {
                            new ProcessBuilder("bash", "-c", "loginctl lock-sessions").start();
                        } catch (Exception ignored) {}
                    }
                    updateStatusAsync(commandId, "SUCCESS", null);
                    log.info("[PERF LOG] [AGENT] LOCK command completed in {}ms", System.currentTimeMillis() - startExecMs);
                    break;

                case "RESTART":
                    log.info("[PERF LOG] [AGENT] Executing instant RESTART command at {}", Instant.now());
                    // Acknowledge SUCCESS to backend before shutting down network stack
                    updateStatusSync(commandId, "SUCCESS", null);
                    try {
                        new com.neurosys.agent.sender.MetricsSender().sendOfflineNotification("Remote RESTART initiated");
                    } catch (Exception ignored) {}

                    if (isWindows) {
                        // 1. Direct Windows shutdown /r /f /t 0
                        try {
                            new ProcessBuilder(sys32 + "\\shutdown.exe", "/r", "/f", "/t", "0").start();
                            log.info("[AGENT] shutdown.exe /r /f /t 0 launched");
                        } catch (Exception e) {
                            log.warn("[AGENT] shutdown.exe /r error: {}", e.getMessage());
                        }

                        // 2. PowerShell Restart-Computer -Force fallback
                        try {
                            new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", "Restart-Computer -Force").start();
                        } catch (Exception ignored) {}
                    }
                    log.info("[PERF LOG] [AGENT] RESTART processes launched in {}ms", System.currentTimeMillis() - startExecMs);
                    break;

                case "SHUTDOWN":
                    log.info("[PERF LOG] [AGENT] Executing instant SHUTDOWN command at {}", Instant.now());
                    // Acknowledge SUCCESS to backend before shutting down network stack
                    updateStatusSync(commandId, "SUCCESS", null);
                    try {
                        new com.neurosys.agent.sender.MetricsSender().sendOfflineNotification("Remote SHUTDOWN initiated");
                    } catch (Exception ignored) {}

                    if (isWindows) {
                        // 1. Direct Windows shutdown /s /f /t 0
                        try {
                            new ProcessBuilder(sys32 + "\\shutdown.exe", "/s", "/f", "/t", "0").start();
                            log.info("[AGENT] shutdown.exe /s /f /t 0 launched");
                        } catch (Exception e) {
                            log.warn("[AGENT] shutdown.exe /s error: {}", e.getMessage());
                        }

                        // 2. PowerShell Stop-Computer -Force fallback
                        try {
                            new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", "Stop-Computer -Force").start();
                        } catch (Exception ignored) {}
                    }
                    log.info("[PERF LOG] [AGENT] SHUTDOWN processes launched in {}ms", System.currentTimeMillis() - startExecMs);
                    break;

                default:
                    log.warn("Unknown power command type: {}", commandType);
                    updateStatusAsync(commandId, "FAILED", "Unsupported command type: " + commandType);
                    break;
            }
        } catch (Exception e) {
            log.error("Failed to execute power command {}", commandType, e);
            updateStatusAsync(commandId, "FAILED", e.getMessage());
        }
    }

    private void updateStatusSync(String commandId, String status, String failureReason) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("commandId", commandId);
            payload.put("agentId", AgentConfig.getAgentId());
            payload.put("status", status);
            if (failureReason != null) {
                payload.put("failureReason", failureReason);
            }

            String jsonBody = objectMapper.writeValueAsString(payload);
            String url = AgentConfig.getServerUrl() + "/agent/power-commands/status";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(2))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            log.info("[PERF LOG] [AGENT] Synchronously reported command {} status: {}", commandId, status);
        } catch (Exception e) {
            log.warn("Failed to synchronously report status before power action: {}", e.getMessage());
        }
    }

    private void updateStatusAsync(String commandId, String status, String failureReason) {
        CompletableFuture.runAsync(() -> updateStatusSync(commandId, status, failureReason));
    }
}

