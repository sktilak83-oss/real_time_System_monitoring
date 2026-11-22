package com.neurosys.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurosys.backend.dto.response.CrashPredictionResponse;
import com.neurosys.backend.entity.Computer;
import com.neurosys.backend.entity.DiagnosticEvent;
import com.neurosys.backend.entity.Prediction;
import com.neurosys.backend.entity.SystemMetric;
import com.neurosys.backend.enums.PredictionType;
import com.neurosys.backend.exception.ResourceNotFoundException;
import com.neurosys.backend.repository.ComputerRepository;
import com.neurosys.backend.repository.DiagnosticEventRepository;
import com.neurosys.backend.repository.PredictionRepository;
import com.neurosys.backend.repository.SystemMetricRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class CrashPredictionServiceImpl implements CrashPredictionService {

    private final ComputerRepository computerRepository;
    private final SystemMetricRepository systemMetricRepository;
    private final DiagnosticEventRepository diagnosticEventRepository;
    private final PredictionRepository predictionRepository;
    private final ObjectMapper objectMapper;

    private static final String MODEL_VERSION = "NeuroSys Hybrid Linear-Logistic ML Engine v2.0";
    private static final int MIN_HISTORICAL_SAMPLES = 5;
    private static final long CACHE_VALIDITY_MINUTES = 3;

    // Fast, thread-safe in-memory cache for deterministic page-refresh consistency
    private final Map<String, CrashPredictionResponse> cacheMap = new ConcurrentHashMap<>();

    @Override
    @Transactional(readOnly = true)
    public CrashPredictionResponse evaluateCrashRisk(String computerId) {
        Computer computer = computerRepository.findById(computerId)
                .orElseThrow(() -> new ResourceNotFoundException("Computer", "id", computerId));

        // 1. Fetch historical telemetry metric samples for THIS computer
        List<SystemMetric> rawHistory = systemMetricRepository.findByComputerIdOrderByRecordedAtDesc(
                computerId, PageRequest.of(0, 100));

        if (rawHistory == null || rawHistory.size() < MIN_HISTORICAL_SAMPLES) {
            int sampleCount = rawHistory != null ? rawHistory.size() : 0;
            log.info("[INFO] Insufficient historical data for computer {} ({}/{} samples). Returning Data Sufficiency: Pending.",
                    computer.getHostname(), sampleCount, MIN_HISTORICAL_SAMPLES);

            CrashPredictionResponse pendingResponse = CrashPredictionResponse.builder()
                    .computerId(computer.getId())
                    .hostname(computer.getHostname())
                    .isDataSufficient(false)
                    .insufficientDataReason(String.format("Collecting initial telemetry baseline for %s (%d/%d samples). Trend forecasting will activate shortly.",
                            computer.getHostname(), sampleCount, MIN_HISTORICAL_SAMPLES))
                    .predictedIssue("Baseline Profiling in Progress")
                    .estimatedTimeframe("Evaluating...")
                    .riskLevel("HEALTHY")
                    .crashProbability(0.02)
                    .confidenceScore(0.50)
                    .confidencePercent(50)
                    .mainFactors(List.of("Collecting initial telemetry trend samples from workstation agent."))
                    .reasons(List.of("Continue running the agent to build the baseline statistical window."))
                    .contributingFactors(List.of("Baseline learning in progress. Real-time metrics streaming normally."))
                    .historicalData(List.of())
                    .predictedData(List.of())
                    .recommendedAction("Workstation agent is actively streaming telemetry. Baseline model will calibrate automatically.")
                    .modelVersion(MODEL_VERSION)
                    .dataStartDate(Instant.now())
                    .dataEndDate(Instant.now())
                    .predictedAt(Instant.now())
                    .build();

            cacheMap.put(computerId, pendingResponse);
            return pendingResponse;
        }

        // Sort historical telemetry chronologically ascending (t0 -> tN)
        List<SystemMetric> history = new ArrayList<>(rawHistory);
        Collections.reverse(history);

        Instant startDate = history.get(0).getRecordedAt() != null ? history.get(0).getRecordedAt() : Instant.now();
        Instant endDate = history.get(history.size() - 1).getRecordedAt() != null ? history.get(history.size() - 1).getRecordedAt() : Instant.now();

        // 2. Least Squares Linear Regression over real historical metrics
        int n = history.size();
        double[] t = new double[n];
        double[] ram = new double[n];
        double[] cpu = new double[n];
        double[] diskFree = new double[n];

        double sumT = 0, sumRam = 0, sumCpu = 0, sumDiskFree = 0;
        double sumT2 = 0, sumTRam = 0, sumTCpu = 0, sumTDiskFree = 0;

        for (int i = 0; i < n; i++) {
            SystemMetric m = history.get(i);
            t[i] = i; // time index sequence
            ram[i] = m.getMemoryUsagePercent() != null ? m.getMemoryUsagePercent() : 0.0;
            cpu[i] = m.getCpuUsagePercent() != null ? m.getCpuUsagePercent() : 0.0;
            diskFree[i] = m.getDiskFreeGb() != null ? m.getDiskFreeGb() : 100.0;

            sumT += t[i];
            sumRam += ram[i];
            sumCpu += cpu[i];
            sumDiskFree += diskFree[i];

            sumT2 += t[i] * t[i];
            sumTRam += t[i] * ram[i];
            sumTCpu += t[i] * cpu[i];
            sumTDiskFree += t[i] * diskFree[i];
        }

        double denominator = (n * sumT2 - sumT * sumT);
        if (denominator == 0) denominator = 1.0;

        // Slopes (Velocity per sample index)
        double slopeRam = (n * sumTRam - sumT * sumRam) / denominator;
        double slopeCpu = (n * sumTCpu - sumT * sumCpu) / denominator;
        double slopeDiskFree = (n * sumTDiskFree - sumT * sumDiskFree) / denominator;

        double interceptRam = (sumRam - slopeRam * sumT) / n;

        // Statistical Goodness-of-Fit (R^2) for RAM trajectory
        double meanRam = sumRam / n;
        double ssTot = 0, ssRes = 0;
        for (int i = 0; i < n; i++) {
            double pred = slopeRam * t[i] + interceptRam;
            ssTot += (ram[i] - meanRam) * (ram[i] - meanRam);
            ssRes += (ram[i] - pred) * (ram[i] - pred);
        }
        double rSquared = ssTot > 0 ? Math.max(0.0, 1.0 - (ssRes / ssTot)) : 0.88;

        // Compute Acceleration (Second Derivative) over recent 10 samples
        double recentRamVelocity = 0.0;
        double priorRamVelocity = 0.0;
        if (n >= 10) {
            recentRamVelocity = (ram[n - 1] - ram[n - 5]) / 4.0;
            priorRamVelocity = (ram[n - 5] - ram[n - 9]) / 4.0;
        }
        double ramAcceleration = recentRamVelocity - priorRamVelocity;

        // Current Latest State
        SystemMetric latestMetric = history.get(n - 1);
        double currentRam = latestMetric.getMemoryUsagePercent() != null ? latestMetric.getMemoryUsagePercent() : 0.0;
        double currentCpu = latestMetric.getCpuUsagePercent() != null ? latestMetric.getCpuUsagePercent() : 0.0;
        double currentDiskFree = latestMetric.getDiskFreeGb() != null ? latestMetric.getDiskFreeGb() : 100.0;
        double currentDiskPercent = latestMetric.getDiskUsagePercent() != null ? latestMetric.getDiskUsagePercent() : 0.0;
        int activeProcesses = latestMetric.getActiveProcessCount() != null ? latestMetric.getActiveProcessCount() : 0;

        // Fetch Diagnostic Crash History (last 30 days)
        Instant thirtyDaysAgo = Instant.now().minus(30, ChronoUnit.DAYS);
        List<DiagnosticEvent> recentEvents = diagnosticEventRepository.findByComputerIdOrderByOccurredAtDesc(
                computerId, PageRequest.of(0, 50)).stream()
                .filter(e -> e.getOccurredAt() != null && e.getOccurredAt().isAfter(thirtyDaysAgo))
                .toList();

        long crashCount = recentEvents.stream()
                .filter(e -> e.getCategory() != null && e.getCategory().name().contains("CRASH"))
                .count();

        // 3. Multivariate Logistic Sigmoid Classifier for Crash Risk Probability
        // z = w0 + w_ram*RAM + w_cpu*CPU + w_vram*V_RAM + w_accel*Accel + w_proc*Processes + w_crash*Crashes
        double z = -3.8; // Baseline logit: ~2.2% baseline risk for healthy computers

        // Memory load weights (Non-linear threshold penalties)
        if (currentRam > 92.0) {
            z += 2.2;
        } else if (currentRam > 85.0) {
            z += 1.2;
        } else if (currentRam > 75.0) {
            z += 0.4;
        }

        // Memory leak velocity & acceleration penalty
        if (slopeRam > 0.15) {
            z += 1.4;
        } else if (slopeRam > 0.05) {
            z += 0.6;
        }
        if (ramAcceleration > 0.1) {
            z += 0.5; // Exponential runaway growth
        }

        // CPU load weights
        if (currentCpu > 92.0) {
            z += 1.8;
        } else if (currentCpu > 80.0) {
            z += 0.8;
        }

        // Low disk storage pressure
        if (currentDiskFree < 8.0 || currentDiskPercent > 94.0) {
            z += 1.5;
        } else if (currentDiskFree < 15.0) {
            z += 0.6;
        }

        // Process overload
        if (activeProcesses > 350) {
            z += 0.9;
        } else if (activeProcesses > 250) {
            z += 0.3;
        }

        // Historical instability penalty
        if (crashCount > 0) {
            z += Math.min(2.5, crashCount * 0.9);
        }

        // Sigmoid Activation Function: P = 1 / (1 + e^-z)
        double crashProbability = 1.0 / (1.0 + Math.exp(-z));
        crashProbability = Math.round(Math.min(0.98, Math.max(0.01, crashProbability)) * 100.0) / 100.0;

        // Model Confidence Calculation (Statistical Goodness of Fit + Sample Volume)
        double sampleFactor = Math.min(1.0, n / 40.0);
        int confidencePercent = Math.min(97, Math.max(72, (int) (72 + (rSquared * 15.0) + (sampleFactor * 10.0))));
        double confidenceScore = confidencePercent / 100.0;

        // 4. Time-to-Exhaustion (TTE) & Predictive Issue Categorization
        String predictedIssue;
        String estimatedTimeframe;
        String riskLevel;
        String recommendedAction;

        // Evaluate Time-to-Out-Of-Memory (OOM) via Linear Regression Slope
        boolean memoryLeakDetected = slopeRam > 0.04 && currentRam >= 65.0;
        Integer minutesToOom = null;
        if (slopeRam > 0.01) {
            double ramHeadroom = Math.max(1.0, 95.0 - currentRam);
            // Assuming average sampling interval ~15 seconds (4 samples/min)
            double ramGrowthPerMin = slopeRam * 4.0;
            if (ramGrowthPerMin > 0.05) {
                minutesToOom = (int) Math.round(ramHeadroom / ramGrowthPerMin);
            }
        }

        if (crashProbability >= 0.65) {
            riskLevel = "CRITICAL";
            predictedIssue = memoryLeakDetected ? "Imminent Out-Of-Memory Crash Risk" : "High System Instability & Kernel Failure Risk";
            estimatedTimeframe = minutesToOom != null && minutesToOom < 180 ?
                    String.format("< %d minutes", Math.max(15, minutesToOom)) : "< 24 hours";
            recommendedAction = "Critical risk detected! Save working files, terminate runaway background processes, or perform a proactive graceful restart.";
        } else if (crashProbability >= 0.35) {
            riskLevel = "ELEVATED";
            if (currentRam >= 85.0 || memoryLeakDetected) {
                predictedIssue = "Memory Performance Degradation & Leak Risk";
                estimatedTimeframe = minutesToOom != null ? String.format("~%d hours", Math.max(1, minutesToOom / 60)) : "~2-3 days";
                recommendedAction = "Elevated RAM allocation detected. Clear browser cache, purge unused application threads, or restart long-running processes.";
            } else if (currentCpu >= 80.0) {
                predictedIssue = "Processor Saturation & Throttling Risk";
                estimatedTimeframe = "~24 to 48 hours";
                recommendedAction = "Sustained high CPU usage detected. Inspect top active processes and reduce thread concurrency.";
            } else if (currentDiskFree < 15.0) {
                predictedIssue = "Disk Capacity Exhaustion Warning";
                estimatedTimeframe = "~3 to 5 days";
                recommendedAction = "Free up disk storage: purge temporary directories, delete old downloaded packages, and empty trash.";
            } else {
                predictedIssue = "Elevated Resource Pressure";
                estimatedTimeframe = "~3 to 7 days";
                recommendedAction = "Workstation operating under elevated demand. Monitor telemetry stability and process allocation.";
            }
        } else {
            riskLevel = "HEALTHY";
            predictedIssue = "Optimal System Performance";
            estimatedTimeframe = "Stable (No failure predicted)";
            recommendedAction = "System operating within healthy nominal parameters. Maintain standard operational monitoring.";
        }

        // 5. Generate Evidence Factors
        List<String> contributingFactors = new ArrayList<>();
        contributingFactors.add(String.format("Current RAM utilization: %.1f%% (Baseline average: %.1f%%).", currentRam, meanRam));
        if (slopeRam > 0.05) {
            contributingFactors.add(String.format("RAM velocity trend: positive growth slope (+%.2f%% per cycle, R²=%.2f).", slopeRam, rSquared));
            if (minutesToOom != null && minutesToOom < 720) {
                contributingFactors.add(String.format("Time-to-Out-Of-Memory (OOM): estimated ~%d minutes until 95%% threshold.", minutesToOom));
            }
        } else {
            contributingFactors.add("RAM trajectory remains balanced with no runaway memory leaks.");
        }

        if (currentCpu >= 80.0) {
            contributingFactors.add(String.format("Elevated CPU utilization recorded: %.1f%%.", currentCpu));
        } else {
            contributingFactors.add(String.format("CPU load is stable at %.1f%% (Nominal operating range).", currentCpu));
        }

        contributingFactors.add(String.format("Storage capacity: %.1f GB free (%.1f%% used).", currentDiskFree, currentDiskPercent));
        if (activeProcesses > 0) {
            contributingFactors.add(String.format("Active OS thread/process count: %d processes.", activeProcesses));
        }
        if (crashCount > 0) {
            contributingFactors.add(String.format("Historical crash log: %d unhandled crash event(s) recorded in past 30 days.", crashCount));
        }

        // 6. Multi-Horizon Graph Data (Historical Actuals + Near-Future Multi-Horizon Projections)
        List<Map<String, Object>> historicalGraph = new ArrayList<>();
        List<Map<String, Object>> predictedGraph = new ArrayList<>();

        // Add recent historical points
        int sampleStep = Math.max(1, n / 15);
        for (int i = 0; i < n; i += sampleStep) {
            SystemMetric m = history.get(i);
            Map<String, Object> point = new HashMap<>();
            String timeStr = m.getRecordedAt() != null ? m.getRecordedAt().toString() : Instant.now().toString();
            point.put("date", timeStr.length() >= 16 ? timeStr.substring(11, 16) : timeStr);
            point.put("actualCpu", Math.round(m.getCpuUsagePercent() != null ? m.getCpuUsagePercent() : 0.0));
            point.put("actualRam", Math.round(m.getMemoryUsagePercent() != null ? m.getMemoryUsagePercent() : 0.0));
            point.put("actualScore", Math.round(calculateHealthScore(m.getCpuUsagePercent(), m.getMemoryUsagePercent(), m.getDiskUsagePercent())));
            point.put("isPrediction", false);
            historicalGraph.add(point);
        }

        // Add Transition Point ("Now")
        double currentHealth = calculateHealthScore(currentCpu, currentRam, currentDiskPercent);
        Map<String, Object> nowPoint = new HashMap<>();
        nowPoint.put("date", "Now");
        nowPoint.put("actualCpu", Math.round(currentCpu));
        nowPoint.put("actualRam", Math.round(currentRam));
        nowPoint.put("actualScore", Math.round(currentHealth));
        nowPoint.put("predictedScore", Math.round(currentHealth));
        nowPoint.put("predictedCpu", Math.round(currentCpu));
        nowPoint.put("predictedRam", Math.round(currentRam));
        nowPoint.put("isPrediction", false);
        historicalGraph.add(nowPoint);

        // Multi-Horizon Predictions: +15m, +1h, +6h, +24h
        String[] horizons = {"+15m", "+1h", "+6h", "+24h"};
        double[] horizonHours = {0.25, 1.0, 6.0, 24.0};

        for (int idx = 0; idx < horizons.length; idx++) {
            double hrs = horizonHours[idx];
            // RAM extrapolation: damped velocity
            double projectedRam = Math.min(99.0, Math.max(15.0, currentRam + (slopeRam * hrs * 12.0)));
            // CPU extrapolation: mean-reverting towards 40%
            double projectedCpu = Math.min(98.0, Math.max(10.0, 40.0 + (currentCpu - 40.0) * Math.exp(-0.25 * hrs) + (slopeCpu * hrs * 4.0)));
            // Disk extrapolation: gradual consumption
            double projectedDisk = Math.min(99.0, Math.max(5.0, currentDiskPercent + (Math.abs(slopeDiskFree) * hrs * 0.1)));

            double projectedHealth = calculateHealthScore(projectedCpu, projectedRam, projectedDisk);

            Map<String, Object> predPoint = new HashMap<>();
            predPoint.put("date", horizons[idx]);
            predPoint.put("predictedCpu", Math.round(projectedCpu));
            predPoint.put("predictedRam", Math.round(projectedRam));
            predPoint.put("predictedScore", Math.round(projectedHealth));
            predPoint.put("isPrediction", true);
            predictedGraph.add(predPoint);
        }

        CrashPredictionResponse response = CrashPredictionResponse.builder()
                .id(UUID.randomUUID().toString())
                .computerId(computer.getId())
                .hostname(computer.getHostname())
                .isDataSufficient(true)
                .insufficientDataReason(null)
                .predictedIssue(predictedIssue)
                .estimatedTimeframe(estimatedTimeframe)
                .riskLevel(riskLevel)
                .crashProbability(crashProbability)
                .confidenceScore(confidenceScore)
                .confidencePercent(confidencePercent)
                .mainFactors(contributingFactors)
                .reasons(contributingFactors)
                .contributingFactors(contributingFactors)
                .historicalData(historicalGraph)
                .predictedData(predictedGraph)
                .recommendedAction(recommendedAction)
                .modelVersion(MODEL_VERSION)
                .dataStartDate(startDate)
                .dataEndDate(endDate)
                .predictedAt(Instant.now())
                .build();

        cacheMap.put(computerId, response);
        log.info("[ML ENGINE] Evaluated future prediction for {} -> Issue: '{}', Risk: {}, Crash Prob: {}%, Confidence: {}%",
                computer.getHostname(), predictedIssue, riskLevel, (int)(crashProbability * 100), confidencePercent);

        return response;
    }

    private double calculateHealthScore(Double cpu, Double ram, Double disk) {
        double c = cpu != null ? cpu : 0.0;
        double r = ram != null ? ram : 0.0;
        double d = disk != null ? disk : 0.0;
        return Math.max(5.0, Math.min(100.0, 100.0 - (0.45 * c + 0.40 * r + 0.15 * d)));
    }

    @Override
    @Transactional(readOnly = true)
    public CrashPredictionResponse getLatestCrashPrediction(String computerId) {
        CrashPredictionResponse cached = cacheMap.get(computerId);
        if (cached != null && cached.getPredictedAt() != null &&
                cached.getPredictedAt().isAfter(Instant.now().minus(CACHE_VALIDITY_MINUTES, ChronoUnit.MINUTES))) {
            return cached;
        }

        return evaluateCrashRisk(computerId);
    }
}
