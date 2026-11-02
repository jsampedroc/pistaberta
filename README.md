# PistaAberta

Plataforma **libre** de reserva de instalacións deportivas municipais, pensada para que cada concello a despregue na súa propia infraestrutura e a poida auditar, adaptar e manter. Candidatura ao I Certame GOBTEC.

- Demo: <https://claude.ai/artifact/B7K4yCzgWTX4BkVz3XeVq5>
- Vídeo: <https://youtu.be/xmkR1CcdhK0>

## O reto

Moitos concellos xestionan as reservas de pistas e pavillóns con follas de cálculo, chamadas telefónicas ou ferramentas privativas das que dependen. As dúas faltas máis custosas son dobres reservas (dúas persoas coa mesma pista á mesma hora) e a mestura de datos entre concellos cando unha mesma plataforma serve a varios.

PistaAberta ataca eses dous problemas na raíz, non nunha pantalla:

- **Unha pista non pode ter dúas reservas confirmadas que se solapen.** Garántese en dúas capas independentes (dominio e base de datos), de modo que resiste tanto a un erro de código como a dúas persoas reservando á vez.
- **Cada concello é un tenant con datos illados.** O illamento aplícase na aplicación e, ademais, na propia base de datos (Row Level Security), de forma que un filtro esquecido non filtra datos doutro concello.

## Licenza

Software libre baixo a **European Union Public Licence v. 1.2 (EUPL-1.2)**: podes usalo, estudalo, modificalo e redistribuílo segundo os termos da licenza. Ver [LICENSE](LICENSE) ou <https://eupl.eu/1.2/en>. Ofrécese «tal cal», sen garantías.

## Estado (honesto)

| Feito e probado | Aínda non feito |
|---|---|
| Fundamento multi-tenant: `tenant_id`, filtro aplicativo e RLS, con falla pechada sen tenant | Catálogo de instalacións e pistas (hoxe `pista` é un esqueleto mínimo) |
| Reservas: crear e cancelar, con non-solapamento en dominio e en BD | API REST de reservas e interface de usuario |
| Rol de aplicación sen privilexios especiais; mínimo privilexio por columna | Autenticación e autorización (en produción non hai resolución de tenant ata a súa especificación) |
| Migracións Flyway e probas de integración con PostgreSQL real | Altas de concellos por unha ferramenta (hoxe, por migración) |

Dito claro: hoxe hai un **núcleo correcto e probado**, non un produto completo para usuarios finais.

## Stack e arquitectura

- Java 21 · Spring Boot 3.3 · Maven multimódulo
- PostgreSQL 16 + PostGIS · Flyway (o esquema só cambia por migracións)
- Probas de integración con Testcontainers (`postgis/postgis:16-3.4`), sen base de datos simulada
- Arquitectura hexagonal / DDD:

| Módulo | Contido | Depende de |
|---|---|---|
| `pistaberta-domain` | Dominio puro | nada (Spring e JPA prohibidos polo `maven-enforcer`) |
| `pistaberta-application` | Casos de uso e portos | `domain` (Spring e JPA prohibidos) |
| `pistaberta-infrastructure` | Spring Boot, Flyway, adaptadores | `application` |

Detalle técnico en [ARCHITECTURE.md](ARCHITECTURE.md); decisións en [docs/decisiones.md](docs/decisiones.md).

## Estándares abertos e portabilidade

- Licenza aberta EUPL-1.2 e código completo no repositorio.
- Só tecnoloxías abertas e sen licenza de pago: Java, PostgreSQL/PostGIS, Spring Boot.
- Os datos viven en PostgreSQL estándar (SQL, sen extensións propietarias); o esquema está versionado nas migracións, así que o concello pode desprexar en infraestrutura propia ou xestionada por un provedor á súa elección, e migrar de provedor sen perder datos.
- Fusos horarios: as franxas gárdanse como instantes absolutos (`timestamptz`).
- Sen dependencia de servizos externos en tempo de execución.

## Despregar en local

Requisitos: JDK 21, Maven 3.9+ e Docker.

```bash
docker compose up -d db        # PostgreSQL 16 + PostGIS en localhost:5432
mvn -B verify                  # compila e executa as probas (Testcontainers, necesita Docker)
java -jar pistaberta-infrastructure/target/pistaberta-infrastructure-0.1.0-SNAPSHOT.jar \
     --spring.profiles.active=dev
```

O perfil `dev` trae as credenciais de exemplo de `docker-compose.yml`, dous concellos de proba (A Estrada e Lalín) e a resolución do tenant pola cabeceira `X-Tenant-Id`. Sen perfil, a aplicación arranca como en produción: sen datos de exemplo e sen resolución de tenant.

Configuración por variables de contorna. **Os contrasinais non teñen valor por defecto**: sen `DB_PASSWORD` e `DB_MIGRATION_PASSWORD` a aplicación non arranca.

| Variable | Uso | Defecto |
|---|---|---|
| `DB_URL` | JDBC da base de datos | `jdbc:postgresql://localhost:5432/pistaberta` |
| `DB_USER` / `DB_PASSWORD` | rol da aplicación `pistaberta_app` (suxeito a RLS) | `pistaberta_app` / obrigatorio (dev: `pistaberta_app`) |
| `DB_MIGRATION_USER` / `DB_MIGRATION_PASSWORD` | propietario co que Flyway migra | `pistaberta` / obrigatorio (dev: `pistaberta`) |

Flyway aplica as migracións ao arrancar. A aplicación nunca se conecta como propietario da base de datos.

## Dar de alta un concello

Hoxe as altas fanse **por migración** (decisión D-004: non hai API de alta). Engade un ficheiro `pistaberta-infrastructure/src/main/resources/db/migration/V<n>__alta_<slug>.sql` co seguinte número libre:

```sql
INSERT INTO tenant (id, slug, codigo_ine, nome)
VALUES (gen_random_uuid(), 'a-estrada', '36017', 'A Estrada');
```

- `slug`: minúsculas, díxitos e guións (único); é o que viaxa na cabeceira `X-Tenant-Id`.
- `codigo_ine`: 5 díxitos (único). É un atributo, non a clave, porque as fusións de concellos cambian o código.
- Os concellos de exemplo (A Estrada, Lalín) só existen nos perfís `dev` e `test`, nunca en produción.

## Contribuír

Mira [ARCHITECTURE.md](ARCHITECTURE.md) antes de tocar o esquema ou o acceso a datos. Un cambio é aceptable cando `mvn -B clean verify` está en verde. As regras do proxecto están en [CLAUDE.md](CLAUDE.md).
