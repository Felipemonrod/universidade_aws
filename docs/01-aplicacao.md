# 01 — A Aplicação Spring Boot

## O que é e o que faz

É um sistema web de registros de alunos universitários construído com **Java 17** e **Spring Boot 3.2**.
Permite **listar, cadastrar, editar e excluir** alunos, além de expor uma **API REST JSON** e um **painel de teste de carga**.

---

## Tecnologias usadas

| Tecnologia | Versão | Para que serve |
|---|---|---|
| Java | 17 | Linguagem principal |
| Spring Boot | 3.2 | Framework que configura tudo automaticamente |
| Spring Data JPA + Hibernate | gerenciado pelo Boot | Comunicação com o banco de dados |
| Spring MVC + Thymeleaf | gerenciado pelo Boot | Interface web (HTML renderizado no servidor) |
| Spring Validation | gerenciado pelo Boot | Validação de formulários (campos obrigatórios, email, etc.) |
| Spring Actuator | gerenciado pelo Boot | Endpoints de saúde (`/actuator/health`) usados pelo Kubernetes |
| PostgreSQL | 15 | Banco de dados relacional |
| Flyway | 9.x (via Boot) | Versionamento automático do schema do banco |
| Lombok | latest | Reduz código repetitivo (getters, setters, builders) |

---

## Estrutura de pacotes

```
com.universidade/
│
├── UniversidadeApplication.java     ← Ponto de entrada (@SpringBootApplication)
│
├── model/
│   └── Student.java                 ← Entidade JPA — representa a linha no banco
│
├── dto/
│   └── StudentDTO.java              ← Objeto de transferência de dados (sem detalhes JPA)
│
├── mapper/
│   └── StudentMapper.java           ← Converte Student ↔ StudentDTO
│
├── repository/
│   └── StudentRepository.java       ← Acesso ao banco de dados
│
├── service/
│   ├── StudentService.java          ← Contrato (interface) do que o serviço faz
│   └── impl/
│       └── StudentServiceImpl.java  ← Implementação das regras de negócio
│
├── controller/
│   ├── HomeController.java          ← Redireciona / → /students
│   ├── StudentController.java       ← Controller web (páginas HTML)
│   └── StudentRestController.java   ← API REST JSON em /api/v1/students
│
├── exception/
│   ├── StudentNotFoundException.java    ← Exceção quando aluno não existe
│   └── GlobalExceptionHandler.java      ← Captura todas as exceções da API REST
│
└── stress/
    ├── StressTestStatus.java        ← Record com o estado do teste de carga
    ├── StressTestService.java       ← Lógica do teste de carga + trava de CPU
    └── StressTestController.java    ← Página e endpoints do teste
```

---

## Padrões de projeto aplicados

### 1. Repository Pattern

**Arquivo:** [StudentRepository.java](../src/main/java/com/universidade/repository/StudentRepository.java)

**O que é:** O Repository isola todo o código que fala com o banco de dados em um único lugar.
O resto do sistema nunca escreve SQL — ele pede ao Repository.

**Como funciona:**
```java
// Apenas estender JpaRepository já dá: findAll, findById, save, delete, etc.
public interface StudentRepository extends JpaRepository<Student, Long> {
    // Queries personalizadas declaradas como métodos
    Optional<Student> findByEmail(String email);
    boolean existsByEmailAndIdNot(String email, Long id);
}
```

**Vantagem:** Se um dia mudarmos de PostgreSQL para MySQL, só o Repository precisa mudar.

---

### 2. Service Layer Pattern

**Arquivos:** [StudentService.java](../src/main/java/com/universidade/service/StudentService.java) e [StudentServiceImpl.java](../src/main/java/com/universidade/service/impl/StudentServiceImpl.java)

**O que é:** A camada de serviço contém as **regras de negócio**. Os controllers não tomam decisões de negócio — eles delegam para o Service.

**Como funciona:**
```java
// Interface define o contrato (o que o serviço PODE fazer)
public interface StudentService {
    StudentDTO save(StudentDTO dto);
    StudentDTO update(Long id, StudentDTO dto);
    void deleteById(Long id);
    // ...
}

// Implementação contém as regras (COMO faz)
@Service
public class StudentServiceImpl implements StudentService {
    public StudentDTO save(StudentDTO dto) {
        if (studentRepository.existsByEmail(dto.getEmail())) {
            throw new IllegalArgumentException("Email já cadastrado");
        }
        // só salva se passou nas regras
        return mapper.toDTO(studentRepository.save(mapper.toEntity(dto)));
    }
}
```

**Vantagem:** Podemos trocar a implementação (ex: adicionar cache) sem mudar o controller.

---

### 3. DTO Pattern (Data Transfer Object)

**Arquivo:** [StudentDTO.java](../src/main/java/com/universidade/dto/StudentDTO.java)

**O que é:** O DTO é um objeto **simples e limpo** que trafega entre as camadas.
A entidade JPA (`Student`) tem anotações de banco de dados, auditoria, etc. — coisas que o mundo externo não precisa ver.

**Diferença entre Entity e DTO:**

| Student (Entity) | StudentDTO |
|---|---|
| Tem `@Entity`, `@Table`, `@Column` | POJO simples |
| Tem `createdAt`, `updatedAt` (auditoria) | Não expõe campos internos |
| Gerenciado pelo Hibernate (estado) | Imutável, seguro para serializar |
| Nunca sai do servidor | Vai para o browser / API caller |

---

### 4. Mapper Pattern

**Arquivo:** [StudentMapper.java](../src/main/java/com/universidade/mapper/StudentMapper.java)

**O que é:** Um componente com **responsabilidade única**: transformar objetos de um tipo para outro.

```java
@Component
public class StudentMapper {
    public StudentDTO toDTO(Student student) { ... }
    public Student toEntity(StudentDTO dto) { ... }
    public void updateEntityFromDTO(StudentDTO dto, Student student) { ... }
}
```

**Por que separar?** Sem o Mapper, toda classe que precisasse converter teria o código duplicado.
Com ele, a conversão muda em **um único lugar**.

---

### 5. Builder Pattern

**Arquivo:** [StudentDTO.java](../src/main/java/com/universidade/dto/StudentDTO.java) com `@Builder`

**O que é:** Permite criar objetos com muitos campos de forma legível, sem construtores gigantes.

```java
// Sem Builder (confuso — qual é qual?)
new StudentDTO(null, "João", "Rua A", "SP", "SP", "joao@email.com", "11999");

// Com Builder (claro)
StudentDTO.builder()
    .name("João")
    .city("SP")
    .email("joao@email.com")
    .build();
```

---

### 6. Front Controller Pattern

**Arquivos:** [StudentController.java](../src/main/java/com/universidade/controller/StudentController.java) e [StudentRestController.java](../src/main/java/com/universidade/controller/StudentRestController.java)

**O que é:** Todos os requests HTTP passam por um ponto central (o controller) antes de chegar à lógica.

- `StudentController` cuida das **páginas HTML** — recebe formulários, chama o serviço, retorna templates Thymeleaf.
- `StudentRestController` cuida da **API JSON** — recebe/retorna JSON, usado por apps SPA ou integrações.

Dois controllers separados porque têm responsabilidades diferentes (SRP — Princípio da Responsabilidade Única).

---

### 7. Chain of Responsibility (GlobalExceptionHandler)

**Arquivo:** [GlobalExceptionHandler.java](../src/main/java/com/universidade/exception/GlobalExceptionHandler.java)

**O que é:** Em vez de cada controller ter um `try/catch`, um único handler captura todas as exceções da API REST e retorna responses padronizadas.

```
Request → RestController → Service lança StudentNotFoundException
                                              ↓
                               GlobalExceptionHandler captura
                                              ↓
                               Retorna JSON { status: 404, message: "..." }
```

---

## Como os dados fluem pela aplicação

### Cadastrar um aluno (fluxo completo):

```
1. Usuário preenche o formulário e clica em "Salvar"
   ↓
2. POST /students  →  StudentController.create()
   ↓
3. Spring valida o StudentDTO (@Valid + @NotBlank, @Email, etc.)
   Se inválido → volta para o formulário com mensagens de erro
   ↓
4. StudentController chama studentService.save(dto)
   ↓
5. StudentServiceImpl verifica: email já existe?
   Se sim → lança IllegalArgumentException
   ↓
6. StudentMapper converte StudentDTO → Student (entidade JPA)
   ↓
7. studentRepository.save(student) → Hibernate gera INSERT no PostgreSQL
   ↓
8. PostgreSQL salva e retorna o ID gerado
   ↓
9. StudentMapper converte Student → StudentDTO (com ID agora)
   ↓
10. Controller redireciona para /students com mensagem de sucesso
```

---

## Endpoints disponíveis

### Interface Web (Thymeleaf — retorna HTML)

| Método | URL | Descrição |
|---|---|---|
| GET | `/students` | Lista todos (paginado + busca) |
| GET | `/students/new` | Formulário de cadastro |
| POST | `/students` | Salva novo aluno |
| GET | `/students/{id}/edit` | Formulário de edição |
| POST | `/students/{id}/edit` | Atualiza aluno |
| POST | `/students/{id}/delete` | Remove aluno |
| GET | `/stress-test` | Painel de teste de elasticidade |
| POST | `/stress-test/start` | Inicia o teste de carga |
| POST | `/stress-test/stop` | Para o teste |

### API REST (retorna JSON)

| Método | URL | Descrição |
|---|---|---|
| GET | `/api/v1/students` | Lista paginada (`?page=0&size=10&search=texto`) |
| GET | `/api/v1/students/{id}` | Busca por ID |
| POST | `/api/v1/students` | Cria (body JSON) |
| PUT | `/api/v1/students/{id}` | Atualiza (body JSON) |
| DELETE | `/api/v1/students/{id}` | Remove |
| GET | `/stress-test/status` | Estado atual do stress test (JSON) |

### Health Check (Actuator)

| URL | Quem usa |
|---|---|
| `/actuator/health` | Kubernetes (readiness e liveness probes) |
| `/actuator/metrics` | Monitoramento e HPA |

---

## Flyway — Versionamento do banco

O Flyway executa os arquivos SQL em ordem numérica na inicialização da app.
Se o banco já tem a versão V1 aplicada, ele pula e só aplica versões novas.

```
src/main/resources/db/migration/
├── V1__create_students_table.sql    ← Cria a tabela e os índices
└── V2__insert_sample_data.sql       ← Insere dados de exemplo
```

Isso garante que **qualquer ambiente** (local, Docker, Kubernetes, AWS) tenha
o mesmo schema, sem precisar criar tabelas manualmente.

---

## Configuração por perfil

| Arquivo | Quando é usado |
|---|---|
| `application.properties` | Sempre carregado (base) |
| `application-prod.properties` | Quando `SPRING_PROFILES_ACTIVE=prod` (Kubernetes) |

No perfil `prod`, não há valores padrão para senhas — elas **obrigatoriamente** devem vir
de variáveis de ambiente (injetadas pelo Kubernetes via Secret).
