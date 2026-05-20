# 03 — Kubernetes (K8s)

## O que é Kubernetes?

Kubernetes (K8s) é um sistema de **orquestração de containers**. Enquanto o Docker Compose
roda containers em **uma única máquina**, o Kubernetes distribui containers por um **cluster**
de múltiplas máquinas, cuidando de:

- Manter o número desejado de containers em execução (se um morrer, cria outro)
- Distribuir o tráfego entre os containers (load balancing)
- Escalar automaticamente (mais containers quando há mais carga)
- Fazer deploy sem downtime (rolling updates)
- Injetar configurações e segredos nos containers

**Terminologia:**

| Termo | O que é |
|---|---|
| **Cluster** | Conjunto de máquinas (nodes) gerenciadas pelo K8s |
| **Node** | Uma máquina (física ou virtual) no cluster |
| **Pod** | A menor unidade do K8s — contém um ou mais containers |
| **Deployment** | Define quantos Pods de uma app devem rodar e como |
| **Service** | Endereço fixo (IP virtual) que roteia tráfego para os Pods |
| **Namespace** | Separação lógica de recursos dentro do cluster |
| **ConfigMap** | Armazena configurações não-sensíveis |
| **Secret** | Armazena dados sensíveis (senhas, tokens) |
| **Ingress** | Regras de roteamento HTTP/HTTPS de fora para dentro do cluster |
| **PVC** | PersistentVolumeClaim — solicita espaço em disco persistente |
| **HPA** | HorizontalPodAutoscaler — escala Pods automaticamente |

---

## Arquitetura do cluster

```
Internet / Usuário
      │
      ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Cluster Kubernetes (namespace: universidade)                        │
│                                                                      │
│  ┌──────────────────┐                                               │
│  │    Ingress       │ ← Roteamento por hostname (universidade.local) │
│  └────────┬─────────┘                                               │
│           │                                                          │
│  ┌────────▼─────────────────────────────────┐                       │
│  │  Service: universidade-service (NodePort) │ ← Load balancer       │
│  └────────┬────────────────────────────────┬┘                       │
│           │ round-robin                    │                         │
│  ┌────────▼────────┐          ┌────────────▼────────┐               │
│  │  Pod: app-1     │   ...    │  Pod: app-N          │               │
│  │  Spring Boot    │          │  Spring Boot         │               │
│  │  :8080          │          │  :8080               │               │
│  └────────┬────────┘          └────────────┬─────────┘               │
│           └──────────┬──────────────────────┘                        │
│                      │                                               │
│  ┌───────────────────▼───────────────────────┐                      │
│  │  Service: postgres-service (ClusterIP)    │ ← Só acessível        │
│  └───────────────────┬───────────────────────┘   internamente        │
│                      │                                               │
│  ┌───────────────────▼───────────────────────┐                      │
│  │  Pod: postgres                            │                      │
│  │  PostgreSQL :5432                         │                      │
│  └───────────────────┬───────────────────────┘                      │
│                      │                                               │
│  ┌───────────────────▼───────────────────────┐                      │
│  │  PersistentVolume (disco persistente 5Gi) │                      │
│  └───────────────────────────────────────────┘                      │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Cada arquivo k8s/ explicado

### `namespace.yaml`

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: universidade
```

**O que faz:** Cria um namespace (espaço de nomes) chamado `universidade`.

**Por que é importante:** O namespace isola todos os recursos do projeto.
Sem namespace, todos os Pods/Services ficam no namespace `default`, misturado com
outros projetos do cluster. Com namespace:

- `kubectl get pods -n universidade` — só mostra os pods deste projeto
- Se deletar o namespace, deleta tudo do projeto de uma vez
- Facilita aplicar permissões (RBAC) por namespace

---

### `configmap.yaml`

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: universidade-config
  namespace: universidade
data:
  DB_HOST: "postgres-service"
  DB_PORT: "5432"
  DB_NAME: "universidade"
  SPRING_PROFILES_ACTIVE: "prod"
  SERVER_PORT: "8080"
```

**O que faz:** Armazena configurações **não-sensíveis** como pares chave-valor.

**Por que usar ConfigMap em vez de hardcode no Deployment:**
- Muda a configuração sem recriar a imagem Docker
- O mesmo `app-deployment.yaml` funciona em qualquer ambiente (local, staging, prod) — só muda o ConfigMap
- **Pode** ser versionado no Git (não contém senhas)

**Como é usado:** O Deployment lê cada chave como variável de ambiente:
```yaml
- name: DB_HOST
  valueFrom:
    configMapKeyRef:
      name: universidade-config
      key: DB_HOST
```

---

### `secret.yaml`

```yaml
apiVersion: v1
kind: Secret
type: Opaque
data:
  DB_USERNAME: YWRtaW4=     # "admin" em base64
  DB_PASSWORD: c2VuaGExMjM= # "senha123" em base64
```

**O que faz:** Armazena dados **sensíveis** (senhas, tokens, certificados).

**Diferença do ConfigMap:**
- O Kubernetes pode encriptar Secrets em repouso (etcd encryption)
- Pods montam Secrets em memória (tmpfs) — não ficam no disco
- RBAC pode restringir quem pode ler Secrets
- **NÃO versione este arquivo no Git com valores reais** — use variáveis de CI/CD ou AWS Secrets Manager

**Base64 não é criptografia!** É apenas codificação. O Secret precisa de encriptação no etcd
para ser seguro. Em produção, use o External Secrets Operator (veja `k8s/aws/`).

Para gerar valores base64:
```bash
echo -n "minha_senha" | base64
```

---

### `postgres-pvc.yaml`

```yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: postgres-pvc
spec:
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: 5Gi
```

**O que faz:** Solicita 5GB de disco persistente ao cluster.

**Por que é necessário:** Containers são efêmeros — quando um Pod morre, seus dados somem.
O PVC garante que os dados do PostgreSQL sobrevivam ao reinício do Pod.

**`ReadWriteOnce`:** O volume pode ser montado por apenas um Node de cada vez — adequado para banco de dados.
(Alternativas: `ReadWriteMany` para sistemas de arquivo distribuídos como EFS/NFS)

**Fluxo:**
```
PVC (solicitação) → K8s encontra um PV disponível → monta no Pod do postgres
```
No minikube, o PV é criado automaticamente no disco local da VM.

---

### `postgres-deployment.yaml`

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: postgres
spec:
  replicas: 1
  selector:
    matchLabels:
      app: postgres
  template:
    metadata:
      labels:
        app: postgres
    spec:
      containers:
        - name: postgres
          image: postgres:15-alpine
          ...
          readinessProbe:
            exec:
              command: ["pg_isready", "-U", "admin", "-d", "universidade"]
```

**O que faz:** Define que deve existir **1 Pod** rodando o PostgreSQL.

**Por que `replicas: 1`:** Bancos de dados relacionais têm estado compartilhado — não se pode
simplesmente adicionar réplicas sem configuração especial (replication). Um único Pod é suficiente para dev.

**`readinessProbe`:** O Kubernetes só manda tráfego para o Pod quando `pg_isready` retornar sucesso.
Isso evita que a aplicação tente conectar antes do banco estar pronto.

**`selector.matchLabels`:** O Deployment usa o label `app: postgres` para saber quais Pods ele gerencia.
O Service usa o mesmo label para saber para quais Pods enviar tráfego.

---

### `postgres-service.yaml`

```yaml
apiVersion: v1
kind: Service
metadata:
  name: postgres-service
spec:
  selector:
    app: postgres
  type: ClusterIP
  ports:
    - port: 5432
      targetPort: 5432
```

**O que faz:** Cria um endereço de rede **interno** fixo para o PostgreSQL.

**Por que usar Service em vez de IP do Pod:**
O IP do Pod muda toda vez que ele é recriado. O Service tem um IP virtual estável.
A aplicação sempre conecta em `postgres-service:5432` — não importa quantas vezes o Pod foi recriado.

**`ClusterIP`:** Só acessível de **dentro** do cluster. O banco de dados nunca fica exposto externamente.

---

### `app-deployment.yaml`

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: universidade-app
spec:
  replicas: 2
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1
      maxUnavailable: 0
  template:
    spec:
      containers:
        - name: universidade-app
          image: universidade-app:latest
          resources:
            requests:
              cpu: "250m"
              memory: "384Mi"
            limits:
              cpu: "500m"
              memory: "768Mi"
          readinessProbe:
            httpGet:
              path: /actuator/health
              port: 8080
            initialDelaySeconds: 30
          livenessProbe:
            httpGet:
              path: /actuator/health
              port: 8080
            initialDelaySeconds: 60
```

**`replicas: 2`:** Sempre dois Pods rodando. Se um morrer, o outro continua servindo.

**`RollingUpdate` com `maxUnavailable: 0`:** Durante um deploy, o K8s cria o Pod novo ANTES
de matar o velho. Zero downtime garantido.

```
Deploy novo:    [v1] [v1]     →  [v1] [v1] [v2]  →  [v1] [v2]  →  [v2] [v2]
                                  (+1 surge)          (-1 antigo)
```

**`resources.requests`:** Quanto de CPU/memória o K8s **reserva** para o Pod no Node.
O scheduler usa isso para decidir em qual Node alocar o Pod.

**`resources.limits`:** Máximo que o Pod pode usar.
- CPU: se ultrapassar, o Pod é throttled (desacelerado)
- Memory: se ultrapassar, o Pod é killed (OOMKilled) e reiniciado

**`250m` de CPU:** `m` = millicores. 1000m = 1 vCPU. 250m = 25% de 1 vCPU.

**`readinessProbe`:** Kubernetes chama `/actuator/health` a cada 10s.
Se falhar 3 vezes seguidas → Pod removido do Service (não recebe mais tráfego).
Volta quando passar novamente.

**`livenessProbe`:** Se `/actuator/health` falhar 3 vezes → Pod **reiniciado** automaticamente.

**`initialDelaySeconds: 30`:** Aguarda 30s antes de começar os probes — tempo para o Flyway
executar as migrações e o Spring Boot inicializar completamente.

---

### `app-service.yaml`

```yaml
apiVersion: v1
kind: Service
metadata:
  name: universidade-service
spec:
  selector:
    app: universidade-app
  type: NodePort
  ports:
    - port: 80
      targetPort: 8080
      nodePort: 30080
```

**O que faz:** Expõe a aplicação externamente via porta 30080 em cada Node.

**Por que `NodePort`:** É o tipo mais simples para acesso externo em clusters locais (minikube/kind).
Com minikube: `http://$(minikube ip):30080`

**Diferença dos tipos de Service:**

| Tipo | Acesso | Quando usar |
|---|---|---|
| `ClusterIP` | Só dentro do cluster | Comunicação interna (ex: app → banco) |
| `NodePort` | Via porta do Node (30000–32767) | Desenvolvimento local |
| `LoadBalancer` | Via IP externo do provedor de cloud | Produção na AWS/GCP/Azure |

---

### `hpa.yaml`

Explicado em detalhes no documento [04-elasticidade.md](04-elasticidade.md).

---

### `ingress.yaml`

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  annotations:
    kubernetes.io/ingress.class: "nginx"
spec:
  rules:
    - host: universidade.local
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: universidade-service
                port:
                  number: 80
```

**O que faz:** Roteia requisições HTTP pelo hostname para o Service correto.

**Por que usar Ingress em vez de acessar o Service diretamente:**
Imagine ter 10 microserviços, cada um com seu NodePort. O Ingress permite ter **um único ponto de entrada**
e rotear por hostname ou path:

```
universidade.local/         → universidade-service
universidade.local/api/     → api-service
outro.local/                → outro-service
```

**Pré-requisito:** NGINX Ingress Controller instalado no cluster.
```bash
minikube addons enable ingress
```

**Para acessar localmente:** Adicione ao arquivo hosts:
```
# Windows: C:\Windows\System32\drivers\etc\hosts
# Linux/Mac: /etc/hosts
<IP do minikube>  universidade.local
```

---

## Comandos essenciais do kubectl

```bash
# ── Verificar tudo ────────────────────────────────────────────────────────────
kubectl get all -n universidade

# ── Pods ──────────────────────────────────────────────────────────────────────
kubectl get pods -n universidade
kubectl get pods -n universidade -w          # watch (atualiza em tempo real)
kubectl describe pod <nome> -n universidade  # detalhes + eventos
kubectl logs <nome> -n universidade          # logs do pod
kubectl logs <nome> -n universidade -f       # logs em tempo real

# ── Deployments ───────────────────────────────────────────────────────────────
kubectl get deployments -n universidade
kubectl rollout status deployment/universidade-app -n universidade
kubectl rollout history deployment/universidade-app -n universidade

# ── Services e Ingress ────────────────────────────────────────────────────────
kubectl get services -n universidade
kubectl get ingress -n universidade

# ── Acesso rápido (minikube) ──────────────────────────────────────────────────
minikube service universidade-service -n universidade

# ── Recursos usados ──────────────────────────────────────────────────────────
kubectl top pods -n universidade
kubectl top nodes

# ── Aplicar/remover manifests ─────────────────────────────────────────────────
kubectl apply -f k8s/           # aplica todos os yamls da pasta
kubectl delete -f k8s/          # remove tudo
kubectl delete namespace universidade  # remove o namespace E tudo dentro dele
```

---

## Ordem correta de aplicação dos manifests

```bash
# 1. Namespace primeiro (todos os outros recursos dependem dele)
kubectl apply -f k8s/namespace.yaml

# 2. Configurações (Deployments dependem deles)
kubectl apply -f k8s/configmap.yaml
kubectl apply -f k8s/secret.yaml

# 3. Storage (Deployment do postgres depende do PVC)
kubectl apply -f k8s/postgres-pvc.yaml

# 4. Banco de dados
kubectl apply -f k8s/postgres-deployment.yaml
kubectl apply -f k8s/postgres-service.yaml

# 5. Aguarda o banco ficar pronto
kubectl wait --for=condition=ready pod -l app=postgres -n universidade --timeout=60s

# 6. Aplicação
kubectl apply -f k8s/app-deployment.yaml
kubectl apply -f k8s/app-service.yaml

# 7. Escalonamento automático
kubectl apply -f k8s/hpa.yaml

# 8. Ingress (opcional — requer NGINX Ingress Controller)
kubectl apply -f k8s/ingress.yaml
```

Ou tudo de uma vez (K8s resolve dependências de ordem):
```bash
kubectl apply -f k8s/
```
