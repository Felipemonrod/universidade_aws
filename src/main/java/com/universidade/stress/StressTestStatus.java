package com.universidade.stress;

/**
 * Record imutável que representa o estado atual do teste de elasticidade.
 * Retornado pela API /stress-test/status (JSON) para polling do frontend.
 */
public record StressTestStatus(
        boolean running,
        double cpuUsagePercent,
        int activeThreads,
        int cpuThreshold,
        String message
) {}
