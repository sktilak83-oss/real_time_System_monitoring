package com.neurosys.backend.controller;

import com.neurosys.backend.dto.request.AgentHeartbeatRequest;
import com.neurosys.backend.dto.response.ApiResponse;
import com.neurosys.backend.service.HeartbeatTrackerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping({"/api/v1/agent", "/api/agent"})
@RequiredArgsConstructor
@Tag(name = "Agent Heartbeat Endpoint", description = "High-performance 1-second agent heartbeat receiver")
public class AgentHeartbeatController {

    private final HeartbeatTrackerService heartbeatTrackerService;

    @PostMapping("/heartbeat")
    @Operation(summary = "Ingest Agent Heartbeat", description = "Lightweight 1-second ping from monitoring agent")
    public ResponseEntity<ApiResponse<String>> ingestHeartbeat(@RequestBody AgentHeartbeatRequest request) {
        heartbeatTrackerService.processHeartbeat(request);
        return ResponseEntity.ok(ApiResponse.success("Heartbeat received", "ACK"));
    }

    @PostMapping("/offline")
    @Operation(summary = "Report Agent Going Offline", description = "Instant notification from agent during graceful shutdown")
    public ResponseEntity<ApiResponse<String>> reportAgentOffline(@RequestBody Map<String, String> payload) {
        String agentId = payload.get("agentId");
        String reason = payload.getOrDefault("reason", "System shutdown");
        heartbeatTrackerService.markAgentOffline(agentId, reason);
        return ResponseEntity.ok(ApiResponse.success("Agent marked offline", "OK"));
    }
}
