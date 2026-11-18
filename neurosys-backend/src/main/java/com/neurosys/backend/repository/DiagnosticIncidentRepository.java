package com.neurosys.backend.repository;

import com.neurosys.backend.entity.DiagnosticIncident;
import com.neurosys.backend.enums.DiagnosticCategory;
import com.neurosys.backend.enums.IncidentStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@Repository
public interface DiagnosticIncidentRepository extends JpaRepository<DiagnosticIncident, String> {
    List<DiagnosticIncident> findByComputerIdOrderByDetectedAtDesc(String computerId, Pageable pageable);
    List<DiagnosticIncident> findByComputerIdAndIncidentStatus(String computerId, IncidentStatus incidentStatus);
    Optional<DiagnosticIncident> findFirstByComputerIdAndCategoryAndIncidentStatus(String computerId, DiagnosticCategory category, IncidentStatus incidentStatus);
    long countByIncidentStatus(IncidentStatus incidentStatus);

    @Modifying
    @Query("DELETE FROM DiagnosticIncident di WHERE di.computer.id = :computerId")
    void deleteByComputerId(@Param("computerId") String computerId);
}
