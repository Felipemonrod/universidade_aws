# 06 — Migração para AWS

## Arquitetura alvo na AWS

```
Internet
    │
    ▼
┌──────────────────────────────────────────────────────────────────────────┐
│  AWS                                                                      │
│                                                                          │
│  Route 53 (DNS)                                                          │
│      │ universidade.seudominio.com                                       │
│      ▼                                                                   │
│  Application Load Balancer (ALB)  ← provisionado pelo K8s Ingress       │
│      │ HTTPS:443 → HTTP:80                                               │
│  ┌───▼──────────────────────────────────────────────────────────────┐   │
│  │  Amazon EKS (Kubernetes gerenciado)                               │   │
│  │                                                                   │   │
│  │  ┌─────────────────────────────────────────────────────────────┐ │   │
│  │  │  Node Group (EC2 Auto Scaling Group)                        │ │   │
│  │  │                                                             │ │   │
│  │  │  ┌───────────┐  ┌───────────┐  ┌───────────┐              │ │   │
│  │  │  │  Pod app  │  │  Pod app  │  │  Pod app  │  (HPA: 2–10) │ │   │
│  │  │  │  :8080    │  │  :8080    │  │  :8080    │              │ │   │
│  │  │  └─────┬─────┘  └─────┬─────┘  └─────┬─────┘              │ │   │
│  │  └────────┼──────────────┼───────────────┼────────────────────┘ │   │
│  └───────────┼──────────────┼───────────────┼──────────────────────┘   │
│              └──────────────┴───────────────┘                           │
│                             │ jdbc:5432                                  │
│  ┌──────────────────────────▼───────────────────────────────────────┐   │
│  │  Amazon RDS PostgreSQL (Multi-AZ)                                │   │
│  │  - Backups automáticos diários                                   │   │
│  │  - Failover automático (< 2 min)                                 │   │
│  │  - Read Replicas para escalar leituras                           │   │
│  │  - Sem gestão de SO, disco ou patches                            │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                          │
│  Amazon ECR          → registry de imagens Docker                        │
│  AWS Secrets Manager → credenciais do banco (via External Secrets)       │
│  AWS Certificate Manager (ACM) → certificado TLS/HTTPS                  │
│  AWS CloudWatch      → logs e métricas                                   │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## Diferenças entre ambiente local e AWS

| Componente | Local (minikube) | AWS (EKS) |
|---|---|---|
| Kubernetes | minikube | Amazon EKS (gerenciado) |
| Banco de dados | Pod PostgreSQL no cluster | Amazon RDS PostgreSQL |
| Imagem Docker | Registry local do minikube | Amazon ECR |
| Load Balancer | NodePort (porta 30080) | ALB via AWS Load Balancer Controller |
| Ingress | NGINX Ingress Controller | AWS ALB Ingress Controller |
| Secrets | Secret K8s manual | AWS Secrets Manager + External Secrets Operator |
| Disco persistente | Disco local da VM | EBS (Elastic Block Store) via CSI Driver |
| Certificado TLS | N/A | AWS Certificate Manager (ACM) |
| DNS | /etc/hosts local | Amazon Route 53 |

---

## Serviços AWS utilizados

### Amazon EKS (Elastic Kubernetes Service)
Kubernetes gerenciado pela AWS. A AWS gerencia o control plane (API Server, etcd, scheduler).
Você gerencia apenas os Node Groups (EC2).

**Vantagens sobre minikube:**
- Alta disponibilidade do control plane (3 zonas de disponibilidade)
- Atualizações automáticas gerenciadas
- Integração nativa com outros serviços AWS (IAM, CloudWatch, ECR)

### Amazon RDS PostgreSQL
Banco de dados gerenciado. Substitui o `postgres-deployment.yaml` do cluster local.

**Por que não rodar PostgreSQL no EKS?**
Gerenciar banco de dados em containers é complexo:
- Backups manuais
- Replicação manual para HA
- Patches e upgrades de versão manuais
- Gestão de disco (PVC)

O RDS resolve tudo isso automaticamente.

### Amazon ECR (Elastic Container Registry)
Registry privado de imagens Docker, integrado com IAM.
Os Pods do EKS têm permissão automática via IRSA (IAM Roles for Service Accounts).

### AWS Secrets Manager
Armazena credenciais do banco de forma segura, com:
- Rotação automática de senhas
- Auditoria via CloudTrail
- Integração com o External Secrets Operator

---

## Arquivos em `k8s/aws/`

Os arquivos em `k8s/aws/` substituem os equivalentes de `k8s/` quando implantando na AWS.
Os outros arquivos (`namespace.yaml`, `hpa.yaml`, `postgres-pvc.yaml`, `postgres-deployment.yaml`,
`postgres-service.yaml`) **não são usados na AWS** (banco é o RDS).

| Arquivo local | Arquivo AWS | Diferença principal |
|---|---|---|
| `k8s/configmap.yaml` | `k8s/aws/configmap.yaml` | `DB_HOST` aponta para endpoint do RDS |
| `k8s/secret.yaml` | `k8s/aws/secret.yaml` | Usa External Secrets Operator |
| `k8s/app-deployment.yaml` | `k8s/aws/app-deployment.yaml` | Imagem do ECR, `imagePullPolicy: Always` |
| `k8s/app-service.yaml` | `k8s/aws/app-service.yaml` | `LoadBalancer` com anotações NLB |
| `k8s/ingress.yaml` | `k8s/aws/ingress.yaml` | ALB com HTTPS e ACM |

---

## Passo a passo de migração

### Pré-requisitos

```bash
# Instalar ferramentas
aws --version      # AWS CLI v2
kubectl version    # kubectl
eksctl version     # eksctl (ferramenta para criar clusters EKS)
helm version       # Helm (gerenciador de pacotes K8s)
```

---

### Passo 1 — Criar repositório ECR e fazer push da imagem

```bash
# Cria o repositório
aws ecr create-repository \
  --repository-name universidade-app \
  --region us-east-1

# Login no ECR
aws ecr get-login-password --region us-east-1 | \
  docker login --username AWS --password-stdin \
  <ACCOUNT_ID>.dkr.ecr.us-east-1.amazonaws.com

# Build e push
docker build -t universidade-app:latest .

docker tag universidade-app:latest \
  <ACCOUNT_ID>.dkr.ecr.us-east-1.amazonaws.com/universidade-app:latest

docker push \
  <ACCOUNT_ID>.dkr.ecr.us-east-1.amazonaws.com/universidade-app:latest
```

---

### Passo 2 — Criar o cluster EKS

```bash
eksctl create cluster \
  --name universidade-cluster \
  --region us-east-1 \
  --nodegroup-name workers \
  --node-type t3.medium \
  --nodes-min 2 \
  --nodes-max 5 \
  --managed

# Configura o kubectl para apontar para o cluster
aws eks update-kubeconfig \
  --name universidade-cluster \
  --region us-east-1
```

---

### Passo 3 — Criar banco RDS PostgreSQL

```bash
# Cria o banco (em VPC privada, sem acesso público)
aws rds create-db-instance \
  --db-instance-identifier universidade-db \
  --db-instance-class db.t3.micro \
  --engine postgres \
  --engine-version "15.4" \
  --master-username admin \
  --master-user-password "SenhaSegura123!" \
  --db-name universidade \
  --allocated-storage 20 \
  --storage-type gp3 \
  --multi-az \
  --no-publicly-accessible \
  --vpc-security-group-ids <SECURITY_GROUP_ID>

# Obtém o endpoint (aguarde ~10 min para criar)
aws rds describe-db-instances \
  --db-instance-identifier universidade-db \
  --query "DBInstances[0].Endpoint.Address"
```

---

### Passo 4 — Armazenar credenciais no Secrets Manager

```bash
aws secretsmanager create-secret \
  --name universidade/db \
  --secret-string '{"username":"admin","password":"SenhaSegura123!"}'
```

---

### Passo 5 — Instalar componentes no EKS

```bash
# metrics-server (necessário para HPA)
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml

# AWS Load Balancer Controller
helm repo add eks https://aws.github.io/eks-charts
helm repo update
helm install aws-load-balancer-controller eks/aws-load-balancer-controller \
  -n kube-system \
  --set clusterName=universidade-cluster \
  --set serviceAccount.create=true \
  --set serviceAccount.name=aws-load-balancer-controller

# External Secrets Operator (para buscar segredos do Secrets Manager)
helm repo add external-secrets https://charts.external-secrets.io
helm install external-secrets external-secrets/external-secrets \
  -n external-secrets \
  --create-namespace
```

---

### Passo 6 — Atualizar e aplicar os manifests AWS

```bash
# Edite k8s/aws/configmap.yaml:
# DB_HOST: "seu-endpoint-rds.rds.amazonaws.com"

# Edite k8s/aws/app-deployment.yaml:
# image: "<ACCOUNT_ID>.dkr.ecr.us-east-1.amazonaws.com/universidade-app:latest"

# Edite k8s/aws/ingress.yaml:
# host: "universidade.seudominio.com"
# certificate-arn: "arn:aws:acm:..."

# Aplica namespace e HPA (iguais ao local)
kubectl apply -f k8s/namespace.yaml
kubectl apply -f k8s/hpa.yaml

# Aplica os manifests específicos da AWS
kubectl apply -f k8s/aws/configmap.yaml
kubectl apply -f k8s/aws/secret.yaml
kubectl apply -f k8s/aws/app-deployment.yaml
kubectl apply -f k8s/aws/app-service.yaml
kubectl apply -f k8s/aws/ingress.yaml
```

---

### Passo 7 — Verificar o deploy

```bash
# Status dos pods
kubectl get pods -n universidade

# DNS do ALB (pode levar 2-3 minutos para propagar)
kubectl get ingress -n universidade

# Teste de health
curl https://universidade.seudominio.com/actuator/health
```

---

## Pilares do AWS Well-Architected Framework

| Pilar | Como foi implementado |
|---|---|
| **Excelência Operacional** | Flyway gerencia schema automaticamente; ConfigMap/Secret separam configuração do código; Actuator expõe métricas para CloudWatch |
| **Segurança** | Secrets Manager para credenciais; RDS sem acesso público; SecurityGroups restritos; usuário não-root no container; HTTPS via ACM |
| **Confiabilidade** | HPA mantém mínimo de 2 réplicas; RollingUpdate sem downtime; RDS Multi-AZ com failover automático; Liveness/Readiness probes |
| **Eficiência de Performance** | HPA escala por CPU e memória; RDS com read replicas; Connection pool HikariCP; índices no banco; Multi-stage Docker image |
| **Otimização de Custos** | maxReplicas=10 limita custo máximo; scaleDown com janela de 5min evita flapping; RDS db.t3.micro para POC; Alpine Linux reduz tamanho e custo de registry |
| **Sustentabilidade** | Multi-stage build reduz tamanho da imagem (menos armazenamento/transferência); HPA reduz consumo em períodos ociosos |
