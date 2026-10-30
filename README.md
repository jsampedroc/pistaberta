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
java -jar pistaberta-infrastructure/target/pistaberta-infrastructure-0.1.0-SNAPSHOT.jar
```

Configuración por variables de entorno: `DB_URL`, `DB_USER`, `DB_PASSWORD`
(por defecto apuntan al `docker-compose.yml`). Flyway aplica las migraciones al arrancar.
