package com.universidade.stress;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Serviço de teste de carga (stress test) para demonstrar a elasticidade do Kubernetes.
 *
 * Padrões aplicados:
 *  - Singleton (Spring @Service): uma única instância gerencia o estado do teste.
 *  - Template Method: burnCpu() é o algoritmo base; pode ser estendido com outros tipos de carga.
 *
 * Mecanismo de segurança (trava):
 *  - Uma thread monitora o CPU do sistema a cada 2 segundos.
 *  - Se a carga ultrapassar o threshold configurado, o teste é interrompido automaticamente.
 *  - O usuário também pode parar manualmente a qualquer momento.
 */
@Service
@Slf4j
public class StressTestService {

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ExecutorService executor;
    private volatile int configuredThreads = 0;
    private volatile int configuredThreshold = 80;
    private volatile String stopReason = "";

    /**
     * Inicia o teste de carga.
     *
     * @param threads      número de threads que vão consumir CPU
     * @param cpuThreshold porcentagem máxima de CPU permitida antes de parar automaticamente
     */
    public void start(int threads, int cpuThreshold) {
        if (!running.compareAndSet(false, true)) {
            log.warn("Teste de elasticidade já está em execução.");
            return;
        }

        configuredThreads = Math.max(1, Math.min(threads, 16));
        configuredThreshold = Math.max(10, Math.min(cpuThreshold, 95));
        stopReason = "";

        // +1 thread extra para o monitor
        executor = Executors.newFixedThreadPool(configuredThreads + 1);

        for (int i = 0; i < configuredThreads; i++) {
            executor.submit(this::burnCpu);
        }

        executor.submit(this::monitorAndStop);

        log.info("Stress test iniciado: {} threads, limite CPU: {}%", configuredThreads, configuredThreshold);
    }

    /**
     * Para o teste manualmente.
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            shutdownExecutor();
            log.info("Stress test parado manualmente.");
        }
    }

    /**
     * Retorna o estado atual do teste para o frontend.
     */
    public StressTestStatus getStatus() {
        double cpu = getCpuUsagePercent();
        boolean isRunning = running.get();
        String msg = isRunning
                ? String.format("Executando com %d threads. CPU atual: %.1f%%", configuredThreads, cpu)
                : stopReason.isEmpty() ? "Parado." : "Parado automaticamente: " + stopReason;

        return new StressTestStatus(isRunning, cpu, isRunning ? configuredThreads : 0, configuredThreshold, msg);
    }

    // ─── privados ────────────────────────────────────────────────────────────

    private void burnCpu() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            // Fibonacci sem memoização: exponencialmente custoso — ideal para gerar carga de CPU
            fibonacci(42);
        }
    }

    private long fibonacci(int n) {
        if (n <= 1) return n;
        return fibonacci(n - 1) + fibonacci(n - 2);
    }

    private void monitorAndStop() {
        while (running.get()) {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            double cpu = getCpuUsagePercent();
            log.info("Stress test monitor — CPU: {}%, limite: {}%", String.format("%.1f", cpu), configuredThreshold);

            if (cpu > configuredThreshold) {
                stopReason = String.format("CPU atingiu %.1f%% (limite configurado: %d%%)", cpu, configuredThreshold);
                log.warn("Trava de segurança ativada: {}", stopReason);
                running.set(false);
                shutdownExecutor();
                break;
            }
        }
    }

    private void shutdownExecutor() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
    }

    private double getCpuUsagePercent() {
        try {
            com.sun.management.OperatingSystemMXBean osBean =
                    (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            double load = osBean.getCpuLoad();
            return (load < 0) ? 0.0 : load * 100.0;
        } catch (ClassCastException e) {
            return 0.0;
        }
    }
}
