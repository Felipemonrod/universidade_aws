package com.universidade.stress;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Serviço de teste de carga para demonstrar elasticidade do Kubernetes.
 *
 * Padrões aplicados:
 *  - Singleton (@Service): uma instância gerencia o estado do teste.
 *  - Strategy: estratégia de trava diferente para container vs bare-metal.
 *
 * Como a trava funciona:
 *  - EM CONTAINER (Kubernetes/Docker): trava baseada em TEMPO (MAX_DURATION_MINUTES).
 *    O CPU limit do container (500m) já é a proteção física — K8s não deixa
 *    o pod usar mais que isso, independente do que o código faça.
 *  - FORA DE CONTAINER (dev local sem K8s): trava baseada em CPU do SISTEMA
 *    (getCpuLoad), pois não há limite imposto por cgroup.
 */
@Service
@Slf4j
public class StressTestService {

    private static final int MAX_DURATION_MINUTES = 5;
    private static final boolean IN_CONTAINER = new File("/.dockerenv").exists();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ExecutorService executor;
    private volatile int configuredThreads = 0;
    private volatile int configuredThreshold = 80;
    private volatile String stopReason = "";
    private volatile long startTimeMs = 0;

    static {
        log.info("StressTestService: ambiente={}", IN_CONTAINER ? "CONTAINER" : "LOCAL");
    }

    public void start(int threads, int cpuThreshold) {
        if (!running.compareAndSet(false, true)) {
            log.warn("Teste já está em execução.");
            return;
        }

        configuredThreads = Math.max(1, Math.min(threads, 16));
        configuredThreshold = Math.max(10, Math.min(cpuThreshold, 95));
        stopReason = "";
        startTimeMs = System.currentTimeMillis();

        executor = Executors.newFixedThreadPool(configuredThreads + 1);

        for (int i = 0; i < configuredThreads; i++) {
            executor.submit(this::burnCpu);
        }
        executor.submit(this::monitorAndStop);

        log.info("Stress test iniciado: {} threads | ambiente={} | threshold={}% | maxDuration={}min",
                configuredThreads, IN_CONTAINER ? "container" : "local",
                configuredThreshold, MAX_DURATION_MINUTES);
    }

    public void stop() {
        if (running.compareAndSet(true, false)) {
            shutdownExecutor();
            log.info("Stress test parado manualmente.");
        }
    }

    public StressTestStatus getStatus() {
        double cpu = getDisplayCpuPercent();
        boolean isRunning = running.get();
        String msg;

        if (isRunning) {
            long elapsed = (System.currentTimeMillis() - startTimeMs) / 1000;
            String cpuLabel = IN_CONTAINER ? "CPU sistema (info)" : "CPU sistema";
            msg = String.format("Executando: %d threads | %s: %.1f%% | Tempo: %ds/%dmin",
                    configuredThreads, cpuLabel, cpu, elapsed, MAX_DURATION_MINUTES);
        } else {
            msg = stopReason.isEmpty() ? "Parado." : "Parado: " + stopReason;
        }

        return new StressTestStatus(isRunning, cpu, isRunning ? configuredThreads : 0, configuredThreshold, msg);
    }

    // ─── privados ────────────────────────────────────────────────────────────

    private void burnCpu() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            fibonacci(38); // ~2s por chamada — gera carga sustentada sem travar o GC
            try {
                Thread.sleep(200); // pausa entre chamadas para evitar starvation das outras threads
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private long fibonacci(int n) {
        if (n <= 1) return n;
        return fibonacci(n - 1) + fibonacci(n - 2);
    }

    private void monitorAndStop() {
        while (running.get()) {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            long elapsedMin = (System.currentTimeMillis() - startTimeMs) / 60_000;
            double cpu = getSystemCpuPercent();

            log.info("Monitor: CPU sistema={}% | tempo={}min | container={}",
                    String.format("%.1f", cpu), elapsedMin, IN_CONTAINER);

            if (IN_CONTAINER) {
                // Em container: só trava por tempo — o CPU limit do pod (500m) protege o hardware
                if (elapsedMin >= MAX_DURATION_MINUTES) {
                    stopReason = String.format("Tempo máximo de %d minutos atingido.", MAX_DURATION_MINUTES);
                    log.info("Trava de tempo: {}", stopReason);
                    running.set(false);
                    shutdownExecutor();
                    break;
                }
            } else {
                // Fora de container: trava por CPU do sistema (sem proteção de cgroup)
                if (cpu > configuredThreshold) {
                    stopReason = String.format("CPU do sistema atingiu %.1f%% (limite: %d%%)", cpu, configuredThreshold);
                    log.warn("Trava de CPU: {}", stopReason);
                    running.set(false);
                    shutdownExecutor();
                    break;
                }
                if (elapsedMin >= MAX_DURATION_MINUTES) {
                    stopReason = String.format("Tempo máximo de %d minutos atingido.", MAX_DURATION_MINUTES);
                    running.set(false);
                    shutdownExecutor();
                    break;
                }
            }
        }
    }

    private void shutdownExecutor() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
    }

    /** CPU do sistema inteiro — usada como trava em ambiente local e como display informativo. */
    private double getSystemCpuPercent() {
        try {
            com.sun.management.OperatingSystemMXBean osBean =
                    (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            double load = osBean.getCpuLoad();
            return (load < 0) ? 0.0 : load * 100.0;
        } catch (ClassCastException e) {
            return 0.0;
        }
    }

    /** Valor exibido na UI — sempre CPU do sistema (informativo). */
    private double getDisplayCpuPercent() {
        return getSystemCpuPercent();
    }
}
