# Documentação — Sistema de Registros de Alunos (XYZ University)

POC de sistema web universitário em **Java + Spring Boot**, containerizado com **Docker**,
orquestrado com **Kubernetes** e preparado para escalonamento automático e deploy na **AWS**.

---

## Documentação por tema

| # | Documento | O que explica |
| --- | --- | --- |
| 1 | [docs/01-aplicacao.md](docs/01-aplicacao.md) | Código Java, padrões de projeto, fluxo de dados, endpoints |
| 2 | [docs/02-docker.md](docs/02-docker.md) | Dockerfile linha a linha, docker-compose, volumes, redes |
| 3 | [docs/03-kubernetes.md](docs/03-kubernetes.md) | Cada arquivo k8s/ explicado em detalhes, kubectl |
| 4 | [docs/04-elasticidade.md](docs/04-elasticidade.md) | HPA, como o escalonamento automático funciona |
| 5 | [docs/05-teste-elasticidade.md](docs/05-teste-elasticidade.md) | Stress test integrado, trava de CPU, como demonstrar o HPA |
| 6 | [docs/06-aws.md](docs/06-aws.md) | Migração para AWS (EKS, RDS, ECR, ALB, Secrets Manager) |

---

## Estrutura do projeto

```text
universidade_aws/
│
├── src/main/java/com/universidade/    ← Código Java (Spring Boot)
├── src/main/resources/
│   ├── templates/                     ← Páginas Thymeleaf (HTML)
│   └── db/migration/                  ← Scripts SQL (Flyway)
│
├── k8s/                               ← Kubernetes LOCAL (minikube)
│   ├── namespace.yaml
│   ├── configmap.yaml
│   ├── secret.yaml
│   ├── postgres-pvc.yaml
│   ├── postgres-deployment.yaml
│   ├── postgres-service.yaml
│   ├── app-deployment.yaml
│   ├── app-service.yaml
│   ├── hpa.yaml
│   └── ingress.yaml
│
├── k8s/aws/                           ← Kubernetes AWS (EKS) — veja docs/06-aws.md
│   ├── configmap.yaml
│   ├── secret.yaml
│   ├── app-deployment.yaml
│   ├── app-service.yaml
│   └── ingress.yaml
│
├── docs/                              ← Documentação detalhada
├── Dockerfile                         ← Multi-stage build
├── docker-compose.yml                 ← Ambiente local completo
└── pom.xml                            ← Dependências Maven
```

---

## Início rápido

### Docker Compose (mais simples)

```bash
docker compose up --build
# Acesse: http://localhost:8080
```

### Kubernetes local (minikube)

```bash
minikube start
eval $(minikube docker-env)
docker build -t universidade-app:latest .
minikube addons enable metrics-server
minikube addons enable ingress
kubectl apply -f k8s/
minikube service universidade-service -n universidade
```

### Demonstrar elasticidade

1. Acesse `/stress-test` na aplicação
2. Configure threads e limite de CPU
3. Observe: `kubectl get hpa -n universidade -w`

---

## As 4 entregas do trabalho

| Entrega | Implementação |
| --- | --- |
| **1. Site com BD** | Spring Boot + PostgreSQL + Thymeleaf + API REST |
| **2. Docker Compose** | `docker-compose.yml` + `Dockerfile` multi-stage |
| **3. Kubernetes** | 10 manifests em `k8s/` |
| **4. Elasticidade** | HPA em `k8s/hpa.yaml` + stress test em `/stress-test` |
