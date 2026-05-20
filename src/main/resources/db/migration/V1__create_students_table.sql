-- Migração inicial: criação da tabela de alunos
-- Flyway executa automaticamente na ordem de versão (V1, V2, ...)

CREATE TABLE IF NOT EXISTS students (
    id          BIGSERIAL       PRIMARY KEY,
    name        VARCHAR(255)    NOT NULL,
    address     VARCHAR(500)    NOT NULL,
    city        VARCHAR(255)    NOT NULL,
    state       VARCHAR(100)    NOT NULL,
    email       VARCHAR(255)    NOT NULL UNIQUE,
    phone       VARCHAR(20)     NOT NULL,
    created_at  TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP       NOT NULL DEFAULT NOW()
);

-- Índices para buscas frequentes
CREATE INDEX IF NOT EXISTS idx_students_name  ON students (LOWER(name));
CREATE INDEX IF NOT EXISTS idx_students_email ON students (email);
CREATE INDEX IF NOT EXISTS idx_students_city  ON students (LOWER(city));
