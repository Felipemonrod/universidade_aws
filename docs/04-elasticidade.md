# 04 — Elasticidade e HPA (Horizontal Pod Autoscaler)

## O que é elasticidade?

Elasticidade é a capacidade de um sistema de **aumentar ou diminuir seus recursos automaticamente**
de acordo com a demanda, sem intervenção humana.

**Sem elasticidade:**
```
Pico de admissões → sistema sobrecarregado → site lento ou fora do ar
Período normal    → recursos ociosos       → desperdício de dinheiro
```

**Com elasticidade:**
```
Pico de admissões → K8s cria mais Pods automaticamente → sistema estável
Período normal    → K8s remove Pods extras             → custo reduzido
```

---

## Como o HPA funciona

O **HorizontalPodAutoscaler (HPA)** é o componente do Kubernetes responsável pela elasticidade horizontal —
ele ajusta o **número de réplicas** (Pods) de um Deployment baseado em métricas.

### Fluxo completo:

```
┌─────────────────────────────────────────────────────────────────────┐
│                                                                     │
│   Pods (app) ──── coleta CPU/memória ────► metrics-server           │
│                                                 │                   │
│                                                 │ API de métricas   │
│                                                 ▼                   │
│   HPA ◄─────── lê métricas agregadas ──── K8s API Server           │
│    │                                                                │
│    │ compara: uso atual vs target                                   │
│    │                                                                │
│    │ uso > target → scale UP   (cria Pods)                         │
│    │ uso < target → scale DOWN (remove Pods)                        │
│    ▼                                                                │
│   Deployment ──── ajusta replicas ────► Node Scheduler             │
│                                              │                      │
│                                              ▼                      │
│                                         Pods criados/removidos      │
└─────────────────────────────────────────────────────────────────────┘
```

### O HPA monitora a cada 15 segundos:

```
réplicas desejadas = ceil(réplicas atuais × (uso atual / target))

Exemplo:
  Pods atuais: 2
  CPU atual:   90% (média dos pods)
  CPU target:  60%
  Réplicas = ceil(2 × 90/60) = ceil(3) = 3 → cria 1 Pod novo
```

---

## O arquivo `k8s/hpa.yaml` explicado

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: universidade-hpa
  namespace: universidade
```

`autoscaling/v2` é a versão atual que suporta múltiplas métricas (CPU + memória ao mesmo tempo).

```yaml
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: universidade-app
```

Aponta para qual Deployment o HPA deve controlar. O HPA ajusta o campo `replicas` deste Deployment.

```yaml
  minReplicas: 2
  maxReplicas: 10
```

**`minReplicas: 2`:** Nunca cai abaixo de 2 Pods, mesmo sem carga.
Garante **alta disponibilidade**: se um Pod morrer ou for re-agendado, o outro continua servindo.

**`maxReplicas: 10`:** Limite de custo. Sem esse limite, um pico de carga poderia criar
centenas de Pods e consumir todos os recursos do cluster (ou gerar uma conta AWS gigante).

```yaml
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 60
    - type: Resource
      resource:
        name: memory
        target:
          type: Utilization
          averageUtilization: 70
```

**Métrica de CPU (`averageUtilization: 60`):**
Quando a **média de CPU** dos Pods ultrapassar 60% do `resources.requests.cpu`,
o HPA cria mais Pods.

Por que 60% e não 100%? Para ter margem de reação. Se esperarmos até 100%, o sistema
já está travado antes de o Pod novo estar pronto (leva ~30s para subir).

**Métrica de memória (`averageUtilization: 70`):**
Escala também quando a memória média ultrapassar 70% — útil para cargas que não são CPU-intensivas
mas consomem muita RAM.

**Como `Utilization` é calculado:**
```
utilization = (uso atual do Pod) / (requests.cpu do Pod) × 100

Ex: Pod usando 300m CPU, requests = 250m → utilization = 120% → escala!
```

Por isso `resources.requests` no Deployment é **obrigatório** para o HPA funcionar.

```yaml
  behavior:
    scaleUp:
      stabilizationWindowSeconds: 30
      policies:
        - type: Pods
          value: 2
          periodSeconds: 60
    scaleDown:
      stabilizationWindowSeconds: 300
      policies:
        - type: Pods
          value: 1
          periodSeconds: 60
```

**`scaleUp.stabilizationWindowSeconds: 30`:** Após detectar necessidade de scale up,
aguarda 30s para confirmar antes de agir. Evita scale up por um pico instantâneo de CPU.

**`scaleUp: value: 2, periodSeconds: 60`:** Adiciona no máximo 2 Pods a cada 60 segundos.
Crescimento gradual — evita criar 8 Pods de uma vez.

**`scaleDown.stabilizationWindowSeconds: 300`:** Aguarda 5 minutos com carga baixa antes de remover Pods.
Esse é o parâmetro mais crítico. Sem ele:

```
CPU 80% → cria pod     ← certo
CPU 50% → remove pod   ← perigoso, pode ser só um momento de trégua
CPU 80% → cria pod     ← de volta ao caos
```

Com 5 minutos de janela, a carga precisa ser consistentemente baixa antes de reduzir.

**`scaleDown: value: 1, periodSeconds: 60`:** Remove apenas 1 Pod por minuto.
Redução segura e gradual.

---

## Pré-requisito: metrics-server

O HPA precisa de um componente chamado **metrics-server** para coletar uso de CPU/memória dos Pods.

**Instalar no minikube:**
```bash
minikube addons enable metrics-server
```

**Verificar se está funcionando:**
```bash
kubectl top pods -n universidade
# Deve mostrar CPU e memória de cada pod
```

Se `kubectl top` retornar erro, o metrics-server ainda não está pronto (aguarde 1-2 minutos).

---

## Como observar o HPA em ação

### Terminal 1 — Observar o HPA:
```bash
kubectl get hpa -n universidade -w
```

Saída típica:
```
NAME                REFERENCE                   TARGETS          MINPODS   MAXPODS   REPLICAS
universidade-hpa   Deployment/universidade-app   5%/60%           2         10        2
universidade-hpa   Deployment/universidade-app   87%/60%          2         10        2        ← carga alta!
universidade-hpa   Deployment/universidade-app   87%/60%          2         10        3        ← criou pod
universidade-hpa   Deployment/universidade-app   63%/60%          2         10        4        ← criou mais
universidade-hpa   Deployment/universidade-app   42%/60%          2         10        4        ← estabilizando
```

### Terminal 2 — Observar os Pods:
```bash
kubectl get pods -n universidade -w
```

### Terminal 3 — Recursos em tempo real:
```bash
watch kubectl top pods -n universidade
```

### Gerar carga (sem o stress test da aplicação):
```bash
# Instalar Apache Benchmark
# Ubuntu/Debian: sudo apt install apache2-utils
# Mac: brew install httpd

APP_URL=$(minikube service universidade-service -n universidade --url)
ab -n 50000 -c 200 $APP_URL/students
```

---

## Por que escalonamento HORIZONTAL e não vertical?

**Vertical (scale up):** Adicionar mais CPU/RAM ao container existente.
- Requer parar e reiniciar o Pod → downtime
- Tem limite físico (não existe máquina infinita)

**Horizontal (scale out):** Adicionar mais Pods iguais.
- Sem downtime — novos Pods são criados enquanto os antigos continuam
- Escala linearmente — se precisar de 10× mais capacidade, cria 10× mais Pods
- O K8s distribui automaticamente entre os Nodes disponíveis

O HPA implementa escalonamento **horizontal** — a escolha correta para web apps stateless como a nossa.

---

## Diagrama de estados do HPA

```
                    ┌──────────────────┐
                    │  Normal (2 pods) │
                    │  CPU: < 60%      │
                    └────────┬─────────┘
                             │ CPU > 60% por 30s
                             ▼
                    ┌──────────────────┐
                    │  Scaling Up      │
                    │  +2 pods/min     │
                    └────────┬─────────┘
                             │ CPU < 60%
                             ▼
                    ┌──────────────────┐
                    │  Estabilizando   │ ← aguarda 5 minutos
                    │  CPU baixo       │
                    └────────┬─────────┘
                             │ CPU baixo por 5min
                             ▼
                    ┌──────────────────┐
                    │  Scaling Down    │
                    │  -1 pod/min      │
                    └────────┬─────────┘
                             │ voltou ao mínimo (2)
                             ▼
                    ┌──────────────────┐
                    │  Normal (2 pods) │
                    └──────────────────┘
```
