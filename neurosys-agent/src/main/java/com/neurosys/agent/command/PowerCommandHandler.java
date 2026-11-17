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
                    boolean lockSucceeded = false;
                    if (isWindows) {
                        // 1. Direct JNA Win32 API User32.INSTANCE.LockWorkStation() (0ms instant in-process execution)
                        try {
                            com.sun.jna.platform.win32.WinDef.BOOL res = com.sun.jna.platform.win32.User32.INSTANCE.LockWorkStation();
                            lockSucceeded = (res != null && res.booleanValue());
                            log.info("[AGENT] JNA User32.LockWorkStation() executed: {}", lockSucceeded);
                        } catch (Throwable t) {
                            log.warn("[AGENT] JNA LockWorkStation call: {}", t.getMessage());
                        }

                        // 2. Direct rundll32 user32.dll,LockWorkStation execution
                        try {
                            Process p = new ProcessBuilder(sys32 + "\\rundll32.exe", "user32.dll,LockWorkStation").start();
                            p.waitFor(500, TimeUnit.MILLISECONDS);
                            lockSucceeded = true;
                            log.info("[AGENT] rundll32 LockWorkStation process executed");
                        } catch (Exception e) {
                            log.warn("[AGENT] rundll32 lock error: {}", e.getMessage());
                        }

                        // 3. Fallback: PowerShell LockWorkStation API call
                        if (!lockSucceeded) {
                            try {
                                new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                                        "(Add-Type '[DllImport(\"user32.dll\")] public static extern bool LockWorkStation();' -Name 'W' -PassThru)::LockWorkStation()")
                                        .start();
                                lockSucceeded = true;
                            } catch (Exception ignored) {}
                        }
                    } else {
                        log.warn("Lock workstation is supported natively on Windows systems.");
                    }
                    updateStatusAsync(commandId, "SUCCESS", null);
                    log.info("[PERF LOG] [AGENT] LOCK command completed in {}ms", System.currentTimeMillis() - startExecMs);
                    break;

                case "RESTART":
                    log.info("[PERF LOG] [AGENT] Executing instant RESTART command at {}", Instant.now());
                    // Acknowledge SUCCESS to backend before shutting down network stack
                    updateStatusSync(commandId, "SUCCESS", null);

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

