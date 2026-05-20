# ─────────────────────────────────────────────────────────────────────────────
# Estágio 1 — Build
# Multi-stage build: a imagem de build não vai para produção, reduzindo o tamanho final.
# ─────────────────────────────────────────────────────────────────────────────
FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app

# Copia o pom.xml primeiro para aproveitar o cache de dependências do Docker.
# Se apenas o código fonte mudar, as dependências não são re-baixadas.
COPY pom.xml .
RUN mvn dependency:go-offline -B --quiet

COPY src ./src
RUN mvn clean package -DskipTests -B --quiet

# ─────────────────────────────────────────────────────────────────────────────
# Estágio 2 — Runtime
# Usa apenas o JRE (não o JDK completo) em imagem Alpine para minimizar tamanho.
# ─────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Usuário não-root para segurança (princípio do menor privilégio)
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

# JVM tuning para containers:
# -XX:+UseContainerSupport  — respeita os limites de CPU/memória do container
# -XX:MaxRAMPercentage=75.0 — usa no máx. 75% da RAM disponível para o heap
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
