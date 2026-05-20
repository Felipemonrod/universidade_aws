# 00 — Como iniciar o projeto

Há duas formas de rodar o projeto. Escolha a que faz sentido para a entrega que está testando.

---

## Opção A — Docker Compose (Entrega 2)

A mais simples. Tudo roda com um único comando.

### Pré-requisitos

- [Docker Desktop](https://www.docker.com/products/docker-desktop/) instalado e **aberto**
- Nenhuma outra instalação necessária

### Passos

```powershell
# 1. Entre no diretório do projeto
cd "c:\Users\felip\Documents\for_code\study\if_act\Java\universidade_aws"

# 2. Sobe banco + aplicação (faz o build automaticamente)
docker compose up --build
```

Aguarde a mensagem:
```
app  | Started UniversidadeApplication in X.XXX seconds
```

**Acesse:** `http://localhost:8080`

### Parar

```powershell
# Ctrl+C no terminal onde está rodando, depois:
docker compose down

# Para apagar tudo (inclusive os dados do banco):
docker compose down -v
```

### Verificar se está funcionando

```powershell
# Health check da aplicação
curl http://localhost:8080/actuator/health

# Listar alunos via API REST
curl http://localhost:8080/api/v1/students
```

---

## Opção B — Kubernetes com minikube (Entregas 3 e 4)

Para testar o Kubernetes, HPA e elasticidade.

### Pré-requisitos

Instale as ferramentas abaixo se ainda não tiver:

| Ferramenta | Link | Para que serve |
| --- | --- | --- |
| Docker Desktop | https://www.docker.com/products/docker-desktop/ | Build de imagens |
| minikube | https://minikube.sigs.k8s.io/docs/start/ | Cluster K8s local |
| kubectl | https://kubernetes.io/docs/tasks/tools/ | Comandos no cluster |

> **Dica Windows:** o Docker Desktop já instala o kubectl. O minikube pode ser instalado via `winget install minikube` ou pelo instalador do site.

### Verificar instalações

```powershell
docker --version
minikube version
kubectl version --client
```

---

### Passo a passo completo

#### 1. Inicie o minikube

```powershell
minikube start --driver=docker --memory=4096 --cpus=2
```

Aguarde a mensagem `Done! kubectl is now configured to use "minikube"`.

> Se aparecer erro de driver, tente: `minikube start --driver=hyperv` ou `--driver=virtualbox`

#### 2. Configure o Docker para usar o registry interno do minikube

**Isso é crítico!** Sem isso, o minikube não encontrará a imagem que você vai construir.

```powershell
# PowerShell (Windows)
minikube -p minikube docker-env | Invoke-Expression
```

> ⚠️ Este comando precisa ser executado **neste mesmo terminal** antes do build.
> Se fechar e abrir outro terminal, execute novamente antes de qualquer `docker build`.

Verifique que funcionou (deve aparecer o docker do minikube, não o local):
```powershell
docker info | Select-String "Name"
# Deve mostrar algo como: Name: minikube
```

#### 3. Faça o build da imagem

```powershell
docker build -t universidade-app:latest .
```

Aguarde o build concluir. Na primeira vez demora ~3-5 minutos (baixa dependências Maven).

Verifique que a imagem existe:
```powershell
docker images universidade-app
```

#### 4. Habilite os addons necessários

```powershell
# metrics-server: OBRIGATÓRIO para o HPA funcionar
minikube addons enable metrics-server

# ingress: opcional, só se quiser acessar por hostname
minikube addons enable ingress
```

#### 5. Aplique os manifests Kubernetes

```powershell
kubectl apply -f k8s/
```

Saída esperada:
```
namespace/universidade created
configmap/universidade-config created
secret/universidade-secret created
persistentvolumeclaim/postgres-pvc created
deployment.apps/postgres created
service/postgres-service created
deployment.apps/universidade-app created
service/universidade-service created
horizontalpodautoscaler.apps/universidade-hpa created
ingress.networking.k8s.io/universidade-ingress created
```

#### 6. Aguarde os pods ficarem prontos

```powershell
kubectl get pods -n universidade -w
```

Aguarde até todos os pods estarem `Running` com `READY 1/1`:
```
NAME                                READY   STATUS    RESTARTS
postgres-xxxx                       1/1     Running   0
universidade-app-xxxx               1/1     Running   0
universidade-app-xxxx               1/1     Running   0
```

> O pod da aplicação demora ~60 segundos para ficar `Running` por causa do
> `initialDelaySeconds: 30` do readinessProbe (tempo para o Flyway rodar as migrações).

Pressione `Ctrl+C` quando todos estiverem `Running`.

#### 7. Acesse a aplicação

```powershell
# Abre automaticamente no browser
minikube service universidade-service -n universidade
```

Ou obtenha a URL manualmente:
```powershell
minikube service universidade-service -n universidade --url
# Exemplo: http://192.168.49.2:30080
```

---

### Verificar a elasticidade (HPA)

```powershell
# Ver status do HPA (TARGETS mostra uso_atual/objetivo)
kubectl get hpa -n universidade

# Saída esperada quando tudo está OK:
# NAME               REFERENCE                   TARGETS          MINPODS MAXPODS REPLICAS
# universidade-hpa   Deployment/universidade-app   5%/60%, 10%/70%   2      10        2
```

> Se TARGETS mostrar `<unknown>/60%`, aguarde 1-2 minutos para o metrics-server coletar dados.

#### Para testar o HPA escalando:

1. **Acesse** `/stress-test` na aplicação
2. Configure: **Threads = 4**, **Limite CPU = 75%**
3. Clique em **Iniciar Teste de Carga**
4. **Em outro terminal**, observe:

```powershell
# Terminal 1: HPA em tempo real
kubectl get hpa -n universidade -w

# Terminal 2: Pods sendo criados
kubectl get pods -n universidade -w
```

Quando a CPU média dos pods ultrapassar 60%, o HPA criará pods novos automaticamente.

---

### Resolver problemas comuns

#### Pods não ficam `Running` — verificar logs

```powershell
# Ver eventos do pod com problema
kubectl describe pod -n universidade -l app=universidade-app

# Ver logs da aplicação
kubectl logs -n universidade -l app=universidade-app --tail=50
```

#### "ImagePullBackOff" ou "ErrImageNeverPull"

A imagem não foi encontrada no registry do minikube. Repita os passos 2 e 3:
```powershell
minikube -p minikube docker-env | Invoke-Expression
docker build -t universidade-app:latest .
kubectl rollout restart deployment/universidade-app -n universidade
```

#### HPA mostra `<unknown>` em TARGETS

O metrics-server ainda não coletou dados ou não está pronto:
```powershell
kubectl get pods -n kube-system | Select-String "metrics"
# Aguarde o pod metrics-server ficar Running
```

#### Porta 30080 recusando conexão

Use `minikube service` em vez de acessar o IP diretamente:
```powershell
minikube service universidade-service -n universidade
```

#### Resetar tudo do zero

```powershell
# Remove todos os recursos do namespace
kubectl delete namespace universidade

# Recria
kubectl apply -f k8s/
```

---

### Parar o minikube

```powershell
# Para o cluster (mantém estado — pode retomar com minikube start)
minikube stop

# Remove o cluster completamente
minikube delete
```

---

## Resumo rápido de comandos

```powershell
# ── Iniciar (Kubernetes) ──────────────────────────────────────────────────────
minikube start --driver=docker --memory=4096 --cpus=2
minikube -p minikube docker-env | Invoke-Expression
docker build -t universidade-app:latest .
minikube addons enable metrics-server
kubectl apply -f k8s/
kubectl get pods -n universidade -w          # aguarda tudo ficar Running
minikube service universidade-service -n universidade

# ── Monitorar ─────────────────────────────────────────────────────────────────
kubectl get all -n universidade
kubectl get hpa -n universidade -w
kubectl top pods -n universidade
kubectl logs -n universidade -l app=universidade-app -f

# ── Redeployar após mudar o código ───────────────────────────────────────────
minikube -p minikube docker-env | Invoke-Expression
docker build -t universidade-app:latest .
kubectl rollout restart deployment/universidade-app -n universidade

# ── Parar ─────────────────────────────────────────────────────────────────────
minikube stop
```
