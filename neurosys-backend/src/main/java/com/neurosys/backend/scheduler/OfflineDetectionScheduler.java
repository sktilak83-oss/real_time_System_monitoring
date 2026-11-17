package com.neurosys.backend.scheduler;

import com.neurosys.backend.config.AlertEngineConfig;
import com.neurosys.backend.entity.Computer;
import com.neurosys.backend.entity.RemotePowerCommand;
import com.neurosys.backend.enums.ComputerStatus;
import com.neurosys.backend.enums.PowerCommandType;
import com.neurosys.backend.repository.ComputerRepository;
import com.neurosys.backend.repository.RemotePowerCommandRepository;
import com.neurosys.backend.service.AlertEngineService;
import com.neurosys.backend.service.HeartbeatTrackerService;
import com.neurosys.backend.service.WebSocketMetricsPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class OfflineDetectionScheduler {

    private final ComputerRepository computerRepository;
    private final AlertEngineService alertEngineService;
    private final HeartbeatTrackerService heartbeatTracker;
    private final WebSocketMetricsPublisher webSocketMetricsPublisher;
    private final AlertEngineConfig alertConfig;
    private final RemotePowerCommandRepository remotePowerCommandRepository;

    @Scheduled(fixedRate = 2000) // Runs every 2 seconds to evaluate endpoint heartbeat health
    @Transactional
    public void detectOfflineComputers() {
        try {
            Instant now = Instant.now();
            long warningStateSec = alertConfig.getOffline().getWarningStateSeconds();
            long alertDurationSec = alertConfig.getOffline().getAlertDurationSeconds();

            Map<String, Instant> heartbeatMap = heartbeatTracker.getLastHeartbeatMap();

            List<Computer> computers = computerRepository.findAll();

            for (Computer c : computers) {
                Instant lastSeen = heartbeatMap.get(c.getId());
                if (lastSeen == null) {
                    lastSeen = heartbeatMap.get(c.getAgentId());
                }
                if (lastSeen == null) {
                    lastSeen = c.getLastSeenAt();
                }

                if (lastSeen == null) {
                    continue;
                }

                long offlineDurationSeconds = Math.max(0, Duration.between(lastSeen, now).getSeconds());

                if (offlineDurationSeconds >= alertDurationSec) {
                    // Endpoint offline >= alertDurationSec -> mark OFFLINE
                    // Suppress alert if a RESTART/SHUTDOWN command was issued within the last 10 minutes (planned reboot)
                    boolean isPlannedReboot = false;
                    try {
                        List<RemotePowerCommand> recentCmds = remotePowerCommandRepository
                                .findByComputerIdOrderByCreatedAtDesc(c.getId());
                        Instant tenMinAgo = now.minus(Duration.ofMinutes(10));
                        isPlannedReboot = recentCmds.stream().anyMatch(cmd ->
                                (cmd.getCommandType() == PowerCommandType.RESTART
                                        || cmd.getCommandType() == PowerCommandType.SHUTDOWN)
                                && cmd.getCreatedAt() != null
                                && cmd.getCreatedAt().isAfter(tenMinAgo));
                    } catch (Exception ignored) {}

                    if (c.getStatus() != ComputerStatus.OFFLINE) {
                        ComputerStatus oldStatus = c.getStatus();
                        log.info("[OFFLINE DETECT] PC {} ({}) missed heartbeat (>{}s). Status {} -> OFFLINE (plannedReboot={})",
                                c.getHostname(), c.getAgentId(), alertDurationSec, oldStatus, isPlannedReboot);
                        c.setStatus(ComputerStatus.OFFLINE);
                        c.setLastCpuUsage(0.0);
                        c.setLastRamUsage(0.0);
                        c.setInternetConnected(false);
                        c.setUpdatedAt(now);
                        computerRepository.save(c);

                        String offlineMsg = offlineDurationSeconds >= 60
                                ? String.format("Telemetry heartbeat stopped for %d minutes", offlineDurationSeconds / 60)
                                : String.format("Telemetry heartbeat stopped (%ds ago)", offlineDurationSeconds);

                        webSocketMetricsPublisher.broadcastStatusChange(c, ComputerStatus.OFFLINE, offlineMsg);
                    }

                    // Do NOT fire offline alerts - status changes are shown in the dashboard only.
                    // Alerts are reserved for genuine hardware/performance issues.

                } else if (offlineDurationSeconds >= warningStateSec) {
                    // Endpoint missed heartbeat for >= warningStateSec -> Mark Warning state
                    if (c.getStatus() == ComputerStatus.ONLINE) {
                        c.setStatus(ComputerStatus.WARNING);
                        c.setUpdatedAt(now);
                        computerRepository.save(c);

                        webSocketMetricsPublisher.broadcastStatusChange(c, ComputerStatus.WARNING,
                                String.format("Telemetry heartbeat delayed (>%ds)", warningStateSec));
                    }
                } else {
                    // Endpoint active within warningStateSec -> Restore to ONLINE if in WARNING or OFFLINE state
                    if (c.getStatus() == ComputerStatus.WARNING || c.getStatus() == ComputerStatus.OFFLINE) {
                        ComputerStatus oldStatus = c.getStatus();
                        c.setStatus(ComputerStatus.ONLINE);
                        c.setUpdatedAt(now);
                        computerRepository.save(c);

                        log.info("[REAL-TIME RESTORE] PC {} ({}) status auto-restored: {} -> ONLINE (last seen {}s ago)",
                                c.getHostname(), c.getAgentId(), oldStatus, offlineDurationSeconds);
                        webSocketMetricsPublisher.broadcastStatusChange(c, ComputerStatus.ONLINE, "Telemetry heartbeat active");
                        // No alert resolution needed - we no longer create offline alerts
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[OFFLINE SCHEDULER] Error during offline check: {}", e.getMessage());
        }
    }
}
