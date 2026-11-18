package com.neurosys.backend.repository;

import com.neurosys.backend.entity.Prediction;
import com.neurosys.backend.enums.PredictionType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PredictionRepository extends JpaRepository<Prediction, String> {
    List<Prediction> findByComputerIdOrderByPredictedAtDesc(String computerId, Pageable pageable);
    Optional<Prediction> findFirstByComputerIdAndPredictionTypeOrderByPredictedAtDesc(String computerId, PredictionType predictionType);

    @Modifying
    @Query("DELETE FROM Prediction p WHERE p.computer.id = :computerId")
    void deleteByComputerId(@Param("computerId") String computerId);
}
