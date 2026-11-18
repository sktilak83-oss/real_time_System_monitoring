package com.neurosys.backend.repository;

import com.neurosys.backend.entity.RemotePowerAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RemotePowerAuditRepository extends JpaRepository<RemotePowerAudit, String> {

    List<RemotePowerAudit> findByComputerIdOrderByTimestampDesc(String computerId);

    List<RemotePowerAudit> findAllByOrderByTimestampDesc();

    @Modifying
    @Query("DELETE FROM RemotePowerAudit a WHERE a.computerId = :computerId")
    void deleteByComputerId(@Param("computerId") String computerId);
}
