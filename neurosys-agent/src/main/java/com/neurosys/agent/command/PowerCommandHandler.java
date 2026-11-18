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
                        // The agent runs as SYSTEM in Session 0 (non-interactive).
                        // LockWorkStation() / rundll32 only work in an interactive user session.
                        // Fix: schedule a one-shot task with /ru INTERACTIVE so Windows runs it in
                        // the currently active user desktop session.

                        // Method 1: schtasks /ru INTERACTIVE — runs in the active user session
                        boolean lockViaScheduler = false;
                        try {
                            // Create a one-shot task that immediately locks using rundll32 in user session
                            String taskName = "NsLockNow_" + System.currentTimeMillis();
                            Process createTask = new ProcessBuilder(
                                sys32 + "\\schtasks.exe",
                                "/create", "/f",
                                "/tn", taskName,
                                "/tr", sys32 + "\\rundll32.exe user32.dll,LockWorkStation",
                                "/sc", "once",
                                "/st", "00:00",
                                "/ru", "INTERACTIVE"
                            ).start();
                            createTask.waitFor(3, TimeUnit.SECONDS);

                            Process runTask = new ProcessBuilder(
                                sys32 + "\\schtasks.exe",
                                "/run", "/tn", taskName
                            ).start();
                            runTask.waitFor(3, TimeUnit.SECONDS);

                            // Cleanup the temp task after a short delay
                            new Thread(() -> {
                                try {
                                    Thread.sleep(5000);
                                    new ProcessBuilder(sys32 + "\\schtasks.exe",
                                        "/delete", "/f", "/tn", taskName).start();
                                } catch (Exception ignored) {}
                            }).start();

                            lockViaScheduler = true;
                            log.info("[AGENT] LOCK via schtasks /ru INTERACTIVE scheduled successfully");
                        } catch (Exception e) {
                            log.warn("[AGENT] schtasks LOCK error: {}", e.getMessage());
                        }

                        // Method 2: PowerShell — query current session user and run lock in their session
                        if (!lockViaScheduler) {
                            try {
                                String psCmd = "$user = (Get-WmiObject Win32_ComputerSystem).UserName; " +
                                    "if ($user) { " +
                                    "  $taskName = 'NsLockPs'; " +
                                    "  $action = New-ScheduledTaskAction -Execute 'rundll32.exe' -Argument 'user32.dll,LockWorkStation'; " +
                                    "  $settings = New-ScheduledTaskSettingsSet; " +
                                    "  Register-ScheduledTask -Force -TaskName $taskName -Action $action -RunLevel Highest -Settings $settings | Out-Null; " +
                                    "  Start-ScheduledTask -TaskName $taskName; " +
                                    "  Start-Sleep -Seconds 3; " +
                                    "  Unregister-ScheduledTask -TaskName $taskName -Confirm:$false | Out-Null; " +
                                    "}";
                                new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", psCmd).start();
                                log.info("[AGENT] LOCK via PowerShell ScheduledTask fallback launched");
                            } catch (Exception e2) {
                                log.warn("[AGENT] PowerShell LOCK fallback error: {}", e2.getMessage());
                            }
                        }
                    } else {
                        // Linux/Mac: lock via screensaver
                        try {
                            new ProcessBuilder("bash", "-c", "loginctl lock-sessions || gnome-screensaver-command -l || xscreensaver-command -lock").start();
                        } catch (Exception ignored) {}
                        log.warn("[AGENT] Lock attempted on non-Windows system.");
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

