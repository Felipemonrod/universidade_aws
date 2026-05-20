# 02 — Docker e Docker Compose

## O que é Docker?

Docker é uma plataforma que permite **empacotar uma aplicação e todas as suas dependências**
(Java, bibliotecas, configurações) dentro de um **container** — uma unidade isolada e portátil.

O container roda da mesma forma em qualquer máquina: no seu notebook, no servidor do colega
ou na nuvem. Isso elimina o famoso "funciona na minha máquina".

**Conceitos básicos:**

| Conceito | Analogia | O que é |
|---|---|---|
| **Image** | Receita de bolo | Template imutável com tudo que a app precisa |
| **Container** | Bolo pronto | Instância em execução de uma image |
| **Dockerfile** | Instruções da receita | Arquivo que descreve como montar a image |
| **Volume** | Pen drive externo | Armazenamento persistente fora do container |
| **Network** | Rede local | Comunicação entre containers |

---

## Dockerfile — linha por linha

```dockerfile
# ── ESTÁGIO 1: BUILD ──────────────────────────────────────────────────────────
FROM maven:3.9.6-eclipse-temurin-17 AS build
```
**Por quê:** Usa uma imagem que já tem o Maven E o JDK 17 instalados.
O alias `build` permite referenciar este estágio depois.

```dockerfile
WORKDIR /app
```
**Por quê:** Define `/app` como diretório de trabalho dentro do container.
Todos os comandos seguintes rodam a partir daqui.

```dockerfile
COPY pom.xml .
RUN mvn dependency:go-offline -B --quiet
```
**Por quê — a parte mais importante do Dockerfile:**
O Docker tem um sistema de **cache de camadas**. Se o `pom.xml` não mudou, ele reutiliza
a camada de dependências já baixadas. Só quando o `pom.xml` muda é que o Maven baixa tudo de novo.

Se fizéssemos `COPY . .` primeiro, qualquer alteração num arquivo `.java` invalidaria o cache
das dependências — re-baixando centenas de MBs desnecessariamente a cada build.

```dockerfile
COPY src ./src
RUN mvn clean package -DskipTests -B --quiet
```
**Por quê:** Copia o código-fonte e compila. `-DskipTests` para não rodar testes no build Docker
(eles são rodados separadamente no CI). `-B` = modo batch (sem interação).

```dockerfile
# ── ESTÁGIO 2: RUNTIME ────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine
```
**Por quê — Multi-stage build:**
Este é o coração do multi-stage build. A imagem final NÃO contém o Maven, o JDK completo,
o código-fonte nem os arquivos intermediários do build. Só o JRE (Java Runtime Environment)
em Alpine Linux (distribuição mínima).

| Estágio | Tamanho aproximado |
|---|---|
| maven:3.9.6-eclipse-temurin-17 | ~600 MB |
| eclipse-temurin:17-jre-alpine | ~90 MB |
| Imagem final (JRE + app.jar) | ~180 MB |

```dockerfile
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser
```
**Por quê:** **Princípio do menor privilégio**. Rodar como `root` dentro do container é um risco
de segurança. Se alguém invadir a app, terá apenas as permissões do usuário `appuser`.

```dockerfile
COPY --from=build /app/target/*.jar app.jar
```
**Por quê:** Copia APENAS o `.jar` compilado do estágio de build para o estágio final.
O estágio de build inteiro é descartado da imagem final.

```dockerfile
ENTRYPOINT ["java",
  "-XX:+UseContainerSupport",
  "-XX:MaxRAMPercentage=75.0",
  "-Djava.security.egd=file:/dev/./urandom",
  "-jar", "app.jar"]
```
**Por quê — cada flag:**
- `-XX:+UseContainerSupport`: a JVM respeita os limites de CPU/memória definidos pelo Docker/Kubernetes (sem isso, a JVM vê a memória total da máquina host, não do container)
- `-XX:MaxRAMPercentage=75.0`: usa no máximo 75% da RAM do container para o heap Java (deixa 25% para o SO e outros processos)
- `-Djava.security.egd=file:/dev/./urandom`: acelera a geração de números aleatórios (sem isso, a JVM pode travar no `/dev/random` em containers sem entropia suficiente)

---

## docker-compose.yml — linha por linha

O Docker Compose orquestra **múltiplos containers** que trabalham juntos.

```yaml
version: "3.9"
```
Versão do formato do arquivo Docker Compose.

### Serviço `db`

```yaml
db:
  image: postgres:15-alpine
```
Usa a imagem oficial do PostgreSQL 15 em Alpine (leve, ~80MB).

```yaml
  environment:
    POSTGRES_DB: universidade
    POSTGRES_USER: admin
    POSTGRES_PASSWORD: senha123
```
Variáveis de ambiente que o PostgreSQL lê na inicialização para criar o banco e o usuário.

```yaml
  ports:
    - "5432:5432"
```
Mapeia a porta 5432 do container para a porta 5432 do seu computador.
Formato: `"HOST:CONTAINER"`. Útil para conectar com um cliente SQL (DBeaver, pgAdmin) localmente.

```yaml
  volumes:
    - postgres_data:/var/lib/postgresql/data
```
**Volume nomeado** — o diretório de dados do PostgreSQL é armazenado fora do container.
Se o container for removido e recriado, os dados **não se perdem**.

```yaml
  healthcheck:
    test: ["CMD-SHELL", "pg_isready -U admin -d universidade"]
    interval: 10s
    timeout: 5s
    retries: 5
    start_period: 10s
```
O Docker verifica a cada 10s se o banco está aceitando conexões.
O serviço `app` só sobe depois que este healthcheck passar.

### Serviço `app`

```yaml
app:
  build:
    context: .
    dockerfile: Dockerfile
```
Em vez de usar uma imagem pronta, Docker executa o `Dockerfile` para construir a imagem da aplicação.

```yaml
  environment:
    SPRING_DATASOURCE_URL: jdbc:postgresql://db:5432/universidade
```
`db` é o **hostname** do container do banco dentro da rede Docker interna.
O Docker Compose cria automaticamente uma rede onde os serviços se comunicam pelo nome do serviço.

```yaml
  depends_on:
    db:
      condition: service_healthy
```
O container `app` só inicia depois que `db` passar no healthcheck.
Sem isso, a app poderia tentar conectar ao banco antes de ele estar pronto — e falhar.

```yaml
  restart: unless-stopped
```
Se o container crashar por algum motivo, Docker reinicia automaticamente.

### Volume

```yaml
volumes:
  postgres_data:
    name: universidade_postgres_data
```
Define o volume nomeado. O nome explícito facilita identificar o volume no `docker volume ls`.

---

## Diagrama de rede Docker Compose

```
┌───────────────────────────────────────────────────┐
│  Rede Docker: universidade_aws_default            │
│                                                   │
│  ┌─────────────────┐    jdbc:5432    ┌──────────┐ │
│  │   app           │ ─────────────► │   db     │ │
│  │  :8080          │                │  :5432   │ │
│  └─────────────────┘                └────┬─────┘ │
│          │                              │        │
└──────────┼──────────────────────────────┼────────┘
           │ porta 8080                   │ porta 5432
      (seu browser)               (DBeaver/pgAdmin)
```

---

## Comandos essenciais

```bash
# Sobe todos os serviços (com build se necessário)
docker compose up --build

# Sobe em background (detached)
docker compose up --build -d

# Ver logs da aplicação em tempo real
docker compose logs -f app

# Ver logs do banco
docker compose logs -f db

# Parar tudo (mantém volumes e dados)
docker compose down

# Parar tudo e apagar volumes (reset total — perde dados!)
docker compose down -v

# Ver status dos containers
docker compose ps

# Acessar o terminal do container da app
docker compose exec app sh

# Acessar o PostgreSQL
docker compose exec db psql -U admin -d universidade
```

---

## Como testar a API REST com curl

```bash
# Listar todos os alunos
curl http://localhost:8080/api/v1/students

# Criar um aluno
curl -X POST http://localhost:8080/api/v1/students \
  -H "Content-Type: application/json" \
  -d '{"name":"Test","address":"Rua A","city":"SP","state":"SP","email":"test@test.com","phone":"11999"}'

# Health check
curl http://localhost:8080/actuator/health
```
