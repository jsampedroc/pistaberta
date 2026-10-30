# PistaAberta

Plataforma libre de reserva de instalacións deportivas municipais. Candidatura ao I Certame GOBTEC.

> Estado: esqueleto (bootstrap). Sin lógica de dominio todavía.

## Licencia

Copyright © los autores de PistaAberta.

Este programa es software libre, distribuido bajo la **European Union Public Licence v. 1.2 (EUPL-1.2)**.
Puedes usarlo, modificarlo y redistribuirlo según los términos de dicha licencia; consulta el fichero
[LICENSE](LICENSE) o <https://eupl.eu/1.2/en>. Se proporciona «tal cual», sin garantías.

## Stack

- Java 21, Spring Boot 3.3, Maven
- Arquitectura hexagonal / DDD
- PostgreSQL 16 + PostGIS, migraciones con Flyway (Flyway-first)
- Tests de integración con Testcontainers (`postgis/postgis`)

## Estructura

| Módulo | Contenido | Dependencias |
|---|---|---|
| `pistaberta-domain` | Dominio puro | ninguna (Spring/JPA prohibidos por maven-enforcer) |
| `pistaberta-application` | Casos de uso y puertos | `domain` (Spring/JPA prohibidos) |
| `pistaberta-infrastructure` | Spring Boot, Flyway, adaptadores | `application` |

## Levantar en local

Requisitos: JDK 21, Maven 3.9+, Docker.

```bash
docker compose up -d db                 # Postgres 16 + PostGIS en localhost:5432
mvn -B verify                           # compila y ejecuta tests (usa Testcontainers, necesita Docker)
java -jar pistaberta-infrastructure/target/pistaberta-infrastructure-0.1.0-SNAPSHOT.jar --spring.profiles.active=dev
```

Configuración por variables de entorno. Las contraseñas **no tienen valor por defecto**: sin `DB_PASSWORD` y `DB_MIGRATION_PASSWORD` la app no arranca. El perfil `dev` trae los valores de `docker-compose.yml`:

| Variable | Uso | Defecto |
|---|---|---|
| `DB_URL` | JDBC de la BD | `jdbc:postgresql://localhost:5432/pistaberta` |
| `DB_USER` / `DB_PASSWORD` | rol de la app `pistaberta_app` (sujeto a RLS); su contraseña también se usa al crearlo en V2 | `pistaberta_app` / (obligatoria; dev: `pistaberta_app`) |
| `DB_MIGRATION_USER` / `DB_MIGRATION_PASSWORD` | owner con el que Flyway migra (necesita `CREATEROLE` para V2) | `pistaberta` / (obligatoria; dev: `pistaberta`) |

Flyway aplica las migraciones al arrancar. Para desarrollo con los concellos de ejemplo
(A Estrada, Lalín) y el resolver por header `X-Tenant-Id`, arranca con el perfil `dev`
(`--spring.profiles.active=dev`). Sin perfil, la app arranca como en producción: sin semilla y sin resolver de tenant.

## Multi-tenant

Cada concello es un tenant con datos aislados (columna `tenant_id`, filtro aplicativo y Row Level Security).
Ver `docs/SDD-multi-tenant.md` y `docs/decisiones.md`.
