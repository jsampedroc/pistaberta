<!-- metodo: v2 -->
# CLAUDE.md — PistaAberta

Plataforma libre de reserva de instalacións deportivas municipais (candidatura I Certame GOBTEC). Licencia EUPL-1.2.

## Rama de integración
- **Base: `main`**. Repo privado `jsampedroc/pistaberta` (se hará público si gana el GOBTEC).
- Ramas `feat/<...>` / `chore/<...>`; PRs independientes desde `origin/main` limpio, **nunca apilados**.
- Nunca merge a `main` sin validación de Pe. Lee y mide contra `origin/main` tras `git fetch`.

## Stack y arquitectura
- Java 21 · Spring Boot 3.3 · Maven multi-módulo · PostgreSQL 16 + PostGIS.
- Hexagonal/DDD: `pistaberta-domain` ← `pistaberta-application` ← `pistaberta-infrastructure`.
- Domain y application NO dependen de Spring ni JPA (maven-enforcer lo hace fallar en `verify`).

## Convenciones
- **Flyway-first**: el esquema solo cambia por migraciones `V<n>__*.sql`; nunca `ddl-auto=update`; nunca editar una migración ya mergeada.
- **Testcontainers** con `postgis/postgis:16-3.4` para integración; sin BD simulada. Testcontainers fijado a 1.21.x (Docker >= 29).
- **Un verde no puede ser mudo**: cada regla con un test que muerde y su mutación aislada; prohibir se verifica.
- **Mutaciones siempre con `mvn clean verify`, nunca `mvn verify`**: sin `clean`, `target/classes` conserva clases/recursos ya borrados (p.ej. migraciones Flyway) y da un falso verde. Un verde sin `clean` no prueba que la mutación muerda.
- Al borrar/mover un recurso o export para una mutación, confirma que el build cae tras `clean`; si no cae, la mutación no está probando nada.
- Bucle local: `mvn -B verify` en verde antes de abrir PR. CI = `mvn -B verify`.
- Keywords de cierre (`closes #n`) solo en el cuerpo del PR / última línea del squash, nunca en commits.
- Cada PR nombra su origen (issue, D-xxx, invariante). Decisiones de dominio ACEPTADAS se apuntan en `docs/decisiones.md` en el mismo PR.
- Dominio nuevo (multi-tenant, reservas): **spec (`/spec`) aprobada primero**, después código.

## Multi-tenant (D-003..D-006, `docs/SDD-multi-tenant.md`)
- Tenant = concello. Aislamiento por `tenant_id` + filtro aplicativo + RLS de Postgres. **Los dos son obligatorios**: ninguna capa sustituye a la otra.
- Toda tabla de datos nueva: `tenant_id uuid not null`, `ENABLE` + `FORCE ROW LEVEL SECURITY` y policy con `app_tenant_id()`, índice que empiece por `tenant_id`, unicidades y FKs con `tenant_id`. `TenantCatalogGuardTest` falla si falta.
- **Todo acceso a datos va dentro de una transacción** (`@Transactional`): el tenant se fija con `set_config(..., true)` al empezar la transacción (`TenantAwareTransactionManager`). Fuera de transacción no hay tenant y la BD devuelve 0 filas (fail-closed). Prohibido el GUC de sesión.
- El acceso aplicativo pasa por `TenantScopedJdbc` (filtro obligatorio; sin tenant lanza `TenantNotSetException`). Async e hilos virtuales con acceso a datos: fuera hasta su spec.
- La app se conecta como `pistaberta_app` (sin superuser ni BYPASSRLS); Flyway migra con el owner (`DB_MIGRATION_USER`). Nunca conectar la app como owner.
- Resolver de tenant por header solo en perfiles `dev`/`test`; prod no tiene resolver hasta la spec de auth. Semilla (`db/seed`) solo dev/test; la tabla de prueba (`db/test`) solo test.
- Mutaciones de aislamiento (una a una, `mvn clean verify`): R1 policy RLS → `TenantRowLevelSecurityTest`; R2 filtro de `TenantScopedJdbc` → `TenantScopedJdbcTest`; R3 `set_config(..., false)` → `TenantPoolIsolationTest`; R4 `NO FORCE` → `TenantCatalogGuardTest`; R5 semilla en `db/migration` → `DefaultProfileTest`.

## Sesiones
- Una sesión con escritura por repo; worktree por sesión. Todo prompt trae SESIÓN + PRECONDICIONES; si falla una, parar y reportar.
