package com.neurosys.backend.service;

import com.neurosys.backend.dto.request.AgentRegistrationRequest;
import com.neurosys.backend.dto.response.AgentRegistrationResponse;
import com.neurosys.backend.entity.Computer;
import com.neurosys.backend.entity.Lab;
import com.neurosys.backend.entity.LabEnrollmentCode;
import com.neurosys.backend.enums.ComputerStatus;
import com.neurosys.backend.repository.ComputerRepository;
import com.neurosys.backend.repository.LabEnrollmentCodeRepository;
import com.neurosys.backend.repository.LabRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRegistrationServiceImpl implements AgentRegistrationService {

    private final ComputerRepository computerRepository;
    private final LabRepository labRepository;
    private final LabEnrollmentCodeRepository enrollmentCodeRepository;

    @Override
    @Transactional
    public AgentRegistrationResponse registerAgent(AgentRegistrationRequest request) {
        log.info("[INFO] Agent registration request received from AgentID: {}, Hostname: {}, MAC: {}, Code: {}", 
                request.getAgentId(), request.getHostname(), request.getMacAddress(), request.getEnrollmentCode());

        // Resolve target lab via enrollment code if provided
        Lab assignedLab = null;
        if (request.getEnrollmentCode() != null && !request.getEnrollmentCode().trim().isEmpty()) {
            String codeStr = request.getEnrollmentCode().trim();
            Optional<LabEnrollmentCode> codeOpt = enrollmentCodeRepository.findByCodeIgnoreCase(codeStr);
            if (codeOpt.isPresent()) {
                LabEnrollmentCode codeObj = codeOpt.get();
                if (!Boolean.TRUE.equals(codeObj.getRevoked()) 
                        && (codeObj.getExpiresAt() == null || codeObj.getExpiresAt().isAfter(Instant.now()))
                        && (codeObj.getMaxUses() == null || codeObj.getCurrentUses() < codeObj.getMaxUses())) {
                    
                    assignedLab = codeObj.getLab();
                    codeObj.setCurrentUses(codeObj.getCurrentUses() + 1);
                    enrollmentCodeRepository.save(codeObj);
                    log.info("[INFO] Validated enrollment code {} → Assigned to Lab: {}", codeStr, assignedLab.getName());
                } else {
                    log.warn("[WARN] Enrollment code {} is expired, revoked, or max uses reached", codeStr);
                }
            }
        }

        // Secondary fallback: if no valid enrollment code was passed, try looking up by labName if specified
        if (assignedLab == null && request.getLabName() != null && !request.getLabName().trim().isEmpty()) {
            String reqLab = request.getLabName().trim();
            Optional<Lab> byName = labRepository.findAll().stream()
                    .filter(l -> l.getName().equalsIgnoreCase(reqLab) || l.getCode().equalsIgnoreCase(reqLab))
                    .findFirst();
            if (byName.isPresent()) {
                assignedLab = byName.get();
                log.info("[INFO] Matched lab by labName '{}' → Assigned to Lab: {}", reqLab, assignedLab.getName());
            }
        }

        // Look up by persistent Agent ID, MAC, or Hostname
        Optional<Computer> existingByAgentId = computerRepository.findByAgentId(request.getAgentId());
        Optional<Computer> existingByMac = request.getMacAddress() != null && !request.getMacAddress().isEmpty() 
                ? computerRepository.findByMacAddress(request.getMacAddress()) 
                : Optional.empty();
        Optional<Computer> existingByHostname = request.getHostname() != null && !request.getHostname().isEmpty()
                ? computerRepository.findByHostnameIgnoreCase(request.getHostname())
                : Optional.empty();

        Computer computer;
        if (existingByAgentId.isPresent() || existingByMac.isPresent() || existingByHostname.isPresent()) {
            computer = existingByAgentId.orElseGet(() -> existingByMac.orElseGet(existingByHostname::get));
            ComputerStatus oldStatus = computer.getStatus();
            log.info("[INFO] Recognized existing computer endpoint: ID={}, Hostname={}", computer.getAgentId(), computer.getHostname());
            
            computer.setAgentId(request.getAgentId());
            computer.setHostname(request.getHostname());
            computer.setComputerName(request.getComputerName() != null ? request.getComputerName() : request.getHostname());
            computer.setIpAddress(request.getIpAddress());
            if (request.getMacAddress() != null && !request.getMacAddress().isEmpty()) {
                computer.setMacAddress(request.getMacAddress());
            }
            computer.setOsName(request.getOsName());
            computer.setOsVersion(request.getOsVersion());
            if (request.getCpuModel() != null) computer.setCpuModel(request.getCpuModel());
            if (request.getTotalRamMb() != null) computer.setTotalRamMb(request.getTotalRamMb());
            if (request.getAgentVersion() != null) computer.setAgentVersion(request.getAgentVersion());
            
            // Only update lab assignment if a valid enrollment code was explicitly supplied
            if (assignedLab != null) {
                computer.setLab(assignedLab);
                computer.setLabName(assignedLab.getName());
                log.info("[INFO] Updated computer {} lab assignment via enrollment code to Lab: {}", computer.getHostname(), assignedLab.getName());
            }
            // Preserve existing computer.getLab() on re-registration/heartbeat! Never overwrite or reset to default!

            computer.setStatus(ComputerStatus.ONLINE);
            computer.setLastSeenAt(Instant.now());
            log.info("[INFO] Computer {} status updated from {} → ONLINE", computer.getHostname(), oldStatus);

        } else {
            log.info("[INFO] New computer agent registered: AgentID={}, Hostname={}", request.getAgentId(), request.getHostname());
            
            // If no valid enrollment code was provided, computer remains unassigned (lab = null) for Super Admin review
            computer = Computer.builder()
                    .agentId(request.getAgentId())
                    .hostname(request.getHostname())
                    .computerName(request.getComputerName() != null ? request.getComputerName() : request.getHostname())
                    .ipAddress(request.getIpAddress())
                    .macAddress(request.getMacAddress() != null ? request.getMacAddress() : "00:00:00:00:00:00")
                    .osName(request.getOsName() != null ? request.getOsName() : "Windows")
                    .osVersion(request.getOsVersion() != null ? request.getOsVersion() : "11")
                    .lab(assignedLab)
                    .labName(assignedLab != null ? assignedLab.getName() : null)
                    .cpuModel(request.getCpuModel())
                    .totalRamMb(request.getTotalRamMb())
                    .agentVersion(request.getAgentVersion())
                    .status(ComputerStatus.ONLINE)
                    .lastSeenAt(Instant.now())
                    .build();
            log.info("[INFO] New PC {} registered as ONLINE with Lab: {}", computer.getHostname(), computer.getLabName() != null ? computer.getLabName() : "UNASSIGNED");
        }

        computer = computerRepository.save(computer);
        String agentToken = "AGENT-AUTH-TOKEN-" + UUID.nameUUIDFromBytes(computer.getAgentId().getBytes());

        return AgentRegistrationResponse.builder()
                .computerId(computer.getId())
                .agentId(computer.getAgentId())
                .status(computer.getStatus().name())
                .agentAuthToken(agentToken)
                .collectionIntervalSeconds(3)
                .registeredAt(computer.getLastSeenAt())
                .build();
    }
}
