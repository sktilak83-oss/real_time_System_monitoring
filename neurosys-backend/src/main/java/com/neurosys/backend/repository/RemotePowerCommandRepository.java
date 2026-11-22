package com.neurosys.backend.repository;

import com.neurosys.backend.entity.RemotePowerCommand;
import com.neurosys.backend.enums.PowerCommandStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RemotePowerCommandRepository extends JpaRepository<RemotePowerCommand, String> {

    @Query("SELECT c FROM RemotePowerCommand c WHERE (c.computer.agentId = :agentId OR LOWER(c.computer.hostname) = LOWER(:agentId) OR c.computer.id = :agentId) AND c.status IN :statuses ORDER BY c.createdAt ASC")
    List<RemotePowerCommand> findPendingCommandsForAgent(@Param("agentId") String agentId, @Param("statuses") java.util.Collection<PowerCommandStatus> statuses);

    Optional<RemotePowerCommand> findFirstByComputerAgentIdAndStatusOrderByCreatedAtAsc(String agentId, PowerCommandStatus status);

    Optional<RemotePowerCommand> findFirstByComputerIdAndStatusInOrderByCreatedAtDesc(String computerId, List<PowerCommandStatus> statuses);

    List<RemotePowerCommand> findByComputerIdOrderByCreatedAtDesc(String computerId);

    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM RemotePowerCommand c WHERE c.computer.id = :computerId")
    void deleteByComputerId(@Param("computerId") String computerId);
}

