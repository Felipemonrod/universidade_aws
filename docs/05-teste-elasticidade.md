# 05 — Teste de Elasticidade (Stress Test)

## O que é e para que serve

A aplicação tem uma página integrada de **stress test** (`/stress-test`) que gera carga de CPU
diretamente dentro do Pod da aplicação.

Isso permite:
1. Demonstrar o HPA escalando Pods em resposta à carga **sem precisar de ferramentas externas**
2. Controlar a intensidade do teste (número de threads e limite de CPU)
3. Ver o escalonamento acontecendo em tempo real no painel web

---

## Trava de segurança

O recurso mais importante do stress test é a **trava automática de CPU**:

```
Usuário configura: limite = 80%
                        │
Teste inicia, threads consomem CPU
                        │
Monitor verifica a cada 2 segundos:
  CPU atual < 80%? → continua
  CPU atual > 80%? → PARA AUTOMATICAMENTE ← trava ativada!
                        │
App exibe: "Parado automaticamente: CPU atingiu 85.3% (limite: 80%)"
```

Isso garante que o computador **nunca** fique travado pelo teste.

---

## Como usar

### 1. Acesse a página

Clique em **Stress Test** na navbar, ou acesse diretamente: `http://localhost:8080/stress-test`

### 2. Configure os parâmetros

| Parâmetro | O que faz | Sugestão |
|---|---|---|
| **Threads de carga** | Quantas threads vão consumir CPU simultaneamente | 2–4 para notebook, 6–8 para desktop |
| **Limite de CPU (trava)** | % máxima de CPU antes de parar automaticamente | 70–80% para segurança |

### 3. Clique em "▶ Iniciar Teste de Carga"

O painel de status atualiza automaticamente a cada 2 segundos mostrando:
- Estado (EXECUTANDO / PARADO)
- CPU atual (com barra de progresso)
- Threads ativas
- Mensagem de status

### 4. Observe o Kubernetes (em outro terminal)

```bash
# Observa o HPA escalando
kubectl get hpa -n universidade -w

# Observa os Pods sendo criados
kubectl get pods -n universidade -w

# Recursos em tempo real
kubectl top pods -n universidade
```

### 5. Para o teste

Clique em **⏹ Parar Teste** ou aguarde a trava automática.
Após parar, observe os Pods sendo removidos gradualmente (~5 minutos — janela do HPA).

---

## Como funciona o código

### `StressTestStatus.java` — Record de estado

```java
public record StressTestStatus(
    boolean running,
    double cpuUsagePercent,
    int activeThreads,
    int cpuThreshold,
    String message
) {}
```

Um **Java Record** (Java 16+) — classe imutável gerada automaticamente com:
- Campos `final`
- Construtor, `equals()`, `hashCode()`, `toString()` automáticos
- Serializado para JSON pelo Jackson (usado no endpoint `/stress-test/status`)

---

### `StressTestService.java` — Lógica principal

#### Estado compartilhado (thread-safe)

```java
private final AtomicBoolean running = new AtomicBoolean(false);
private volatile ExecutorService executor;
private volatile int configuredThreads = 0;
private volatile int configuredThreshold = 80;
private volatile String stopReason = "";
```

**`AtomicBoolean`:** Permite verificar e mudar o estado de forma atômica entre threads.
`compareAndSet(false, true)` só retorna `true` UMA vez — mesmo se chamado por múltiplas threads
simultaneamente. Isso evita iniciar o teste duas vezes.

**`volatile`:** Garante visibilidade entre threads — toda thread vê o valor mais recente
da variável, sem cache local da CPU.

#### Inicialização do teste

```java
public void start(int threads, int cpuThreshold) {
    if (!running.compareAndSet(false, true)) {
        return; // já está rodando — ignora segunda chamada
    }

    // Limita parâmetros a valores seguros
    configuredThreads = Math.max(1, Math.min(threads, 16));
    configuredThreshold = Math.max(10, Math.min(cpuThreshold, 95));

    executor = Executors.newFixedThreadPool(configuredThreads + 1); // +1 para o monitor

    for (int i = 0; i < configuredThreads; i++) {
        executor.submit(this::burnCpu);      // threads de carga
    }
    executor.submit(this::monitorAndStop);   // thread de monitoramento
}
```

#### A função que consome CPU

```java
private void burnCpu() {
    while (running.get() && !Thread.currentThread().isInterrupted()) {
        fibonacci(42);  // Fibonacci(42) = ~267 milhões de chamadas recursivas
    }
}

private long fibonacci(int n) {
    if (n <= 1) return n;
    return fibonacci(n - 1) + fibonacci(n - 2);
}
```

**Por que Fibonacci recursivo sem memoização?**

A versão ingênua do Fibonacci tem complexidade **O(2^n)** — exponencial.
`fibonacci(42)` faz ~267 milhões de chamadas de função, consumindo 100% de 1 thread de CPU.
É previsível, puro e não usa I/O ou memória excessiva — ideal para gerar carga de CPU controlada.

Comparação:
| n | chamadas | tempo aprox. |
|---|---|---|
| 30 | 2.7M | ~20ms |
| 35 | 29M | ~200ms |
| 40 | 331M | ~2s |
| 42 | 867M | ~5s |

#### A thread de monitoramento (trava de segurança)

```java
private void monitorAndStop() {
    while (running.get()) {
        try {
            Thread.sleep(2_000); // verifica a cada 2 segundos
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            break;
        }

        double cpu = getCpuUsagePercent();

        if (cpu > configuredThreshold) {
            stopReason = String.format(
                "CPU atingiu %.1f%% (limite: %d%%)", cpu, configuredThreshold
            );
            running.set(false);
            shutdownExecutor(); // interrompe todas as threads de carga
            break;
        }
    }
}
```

A thread do monitor roda em paralelo com as threads de carga.
Ela dorme 2 segundos, acorda, mede a CPU, e volta a dormir — até a trava disparar ou o teste ser parado.

#### Como a CPU é medida

```java
private double getCpuUsagePercent() {
    try {
        com.sun.management.OperatingSystemMXBean osBean =
            (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        double load = osBean.getCpuLoad(); // [0.0, 1.0] — uso de CPU do sistema inteiro
        return (load < 0) ? 0.0 : load * 100.0;
    } catch (ClassCastException e) {
        return 0.0; // fallback para JVMs sem suporte a sun.management
    }
}
```

`getCpuLoad()` retorna o uso de CPU do **sistema operacional inteiro** (não só da JVM).
Isso é intencional — queremos proteger o computador, não só a JVM.

Valores possíveis:
- `0.0` a `1.0` → porcentagem (multiplicamos por 100 para exibir)
- `-1.0` → não disponível (retornamos 0.0 como fallback)

#### Parada limpa

```java
public void stop() {
    if (running.compareAndSet(true, false)) {
        shutdownExecutor();
    }
}

private void shutdownExecutor() {
    if (executor != null && !executor.isShutdown()) {
        executor.shutdownNow(); // interrompe todas as threads via interrupt
    }
}
```

`shutdownNow()` envia `interrupt()` para todas as threads do pool.
O `while (running.get() && !Thread.currentThread().isInterrupted())` no `burnCpu()`
detecta o interrupt e para naturalmente.

---

### `StressTestController.java` — Rotas

```java
@GetMapping("/status")
@ResponseBody
public StressTestStatus status() {
    return stressTestService.getStatus();
}
```

Este endpoint retorna JSON. O JavaScript da página chama ele a cada 2 segundos:

```javascript
setInterval(() => {
    fetch('/stress-test/status')
        .then(r => r.json())
        .then(s => {
            // atualiza os elementos da página sem reload
            document.getElementById('status-cpu').textContent = s.cpuUsagePercent.toFixed(1) + '%';
            document.getElementById('cpu-bar').style.width = s.cpuUsagePercent + '%';
            // ...
        });
}, 2000);
```

---

## Diagrama de threads durante o teste

```
Thread principal (HTTP request)
    │
    ▼
StressTestService.start(threads=3, threshold=80)
    │
    ├── Executa submit() × 3 → Worker Thread 1: burnCpu() [Fibonacci ∞]
    │                          Worker Thread 2: burnCpu() [Fibonacci ∞]
    │                          Worker Thread 3: burnCpu() [Fibonacci ∞]
    │
    └── Executa submit() × 1 → Monitor Thread: sleep(2s) → getCpuLoad() → ...
                                    │
                                    │ CPU > 80%?
                                    ▼
                               running.set(false)
                               executor.shutdownNow()
                                    │
                               Worker 1,2,3 recebem interrupt
                               saem do while → fim
```

---

## Roteiro completo de demonstração

1. **Inicie o Kubernetes:**
   ```bash
   minikube start
   eval $(minikube docker-env)
   docker build -t universidade-app:latest .
   minikube addons enable metrics-server
   kubectl apply -f k8s/
   ```

2. **Aguarde os Pods ficarem prontos:**
   ```bash
   kubectl get pods -n universidade -w
   # Aguarde: 2/2 Running
   ```

3. **Abra 3 terminais em paralelo:**

   Terminal A: `kubectl get hpa -n universidade -w`
   Terminal B: `kubectl get pods -n universidade -w`
   Terminal C: `kubectl top pods -n universidade`

4. **Acesse a aplicação e inicie o stress test:**
   ```bash
   minikube service universidade-service -n universidade
   # Clique em "Stress Test" e inicie com 4 threads, limite 75%
   ```

5. **Observe (Terminal A):**
   ```
   TARGETS        REPLICAS
   5%/60%         2        ← antes do teste
   78%/60%        2        ← teste iniciado
   78%/60%        3        ← HPA criou pod!
   58%/60%        4        ← criou mais um
   42%/60%        4        ← estabilizando
   ```

6. **Pare o teste** e observe os Pods sendo removidos (~5 min pela janela de scaleDown).

---

## Observação importante sobre containers

Dentro de um container Kubernetes, `getCpuLoad()` retorna o uso de CPU do **node host**,
não do container em si. Isso é adequado para proteger o PC durante a demonstração.

O HPA, por sua vez, usa as métricas do container (coletadas pelo metrics-server via cgroups),
que são mais precisas para decisões de escalonamento.
