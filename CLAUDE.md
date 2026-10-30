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
- Bucle local: `mvn -B verify` en verde antes de abrir PR. CI = `mvn -B clean verify` (ver sección CI).
- Keywords de cierre (`closes #n`) solo en el cuerpo del PR / última línea del squash, nunca en commits.
- Cada PR nombra su origen (issue, D-xxx, invariante). Decisiones de dominio ACEPTADAS se apuntan en `docs/decisiones.md` en el mismo PR.
- Dominio nuevo (multi-tenant, reservas): **spec (`/spec`) aprobada primero**, después código.

## Multi-tenant (D-003..D-006, `docs/SDD-multi-tenant.md`)
- Tenant = concello. Aislamiento por `tenant_id` + filtro aplicativo + RLS de Postgres. **Los dos son obligatorios**: ninguna capa sustituye a la otra.
- Toda tabla de datos nueva: `tenant_id uuid not null`, `ENABLE` + `FORCE ROW LEVEL SECURITY` y policy con `app_tenant_id()`, índice que empiece por `tenant_id`, unicidades y FKs con `tenant_id`, y `GRANT` DML explícito a `pistaberta_app` en su migración (no hay `ALTER DEFAULT PRIVILEGES`). `TenantCatalogGuardTest` revisa TODAS las tablas de `public`: o están en su allowlist de catálogos globales (añadir una exige tocar el test) o fallan.
- **Todo acceso a datos va dentro de una transacción** (`@Transactional`): el tenant se fija con `set_config(..., true)` al empezar la transacción (`TenantAwareTransactionManager`). Fuera de transacción no hay tenant y la BD devuelve 0 filas (fail-closed). Prohibido el GUC de sesión.
- El acceso aplicativo pasa por `TenantScopedJdbc` (`query`/`insert`/`update`/`delete`: el filtro por tenant lo pone el componente; `tenant_id` no se puede reasignar; sin tenant lanza `TenantNotSetException`). Los fragmentos SQL son constantes y los valores van como parámetros: **RLS protege frente a filtros olvidados, no frente a inyección SQL** (el rol de la app puede fijar el GUC). Async e hilos virtuales con acceso a datos: fuera hasta su spec.
- La app se conecta como `pistaberta_app` (sin superuser ni BYPASSRLS); Flyway migra con el owner (`DB_MIGRATION_USER`). Nunca conectar la app como owner. Defensas: V2 falla si el rol existe con SUPERUSER/BYPASSRLS y `AppRoleGuard` impide arrancar con un rol que se salte RLS. Las contraseñas no tienen valor por defecto fuera del perfil `dev`.
- `TenantFilter` cubre **toda** request (deny by default), no solo `/api/**`: filtrar por prefijo de ruta se saltaba con `/api;x=1/...`.
- Resolver de tenant por header solo en perfiles `dev`/`test`; prod no tiene resolver hasta la spec de auth. Semilla (`db/seed`) solo dev/test; la tabla de prueba (`db/test`) solo test.
- Mutaciones de aislamiento (una a una, `mvn clean verify`): R1 policy RLS → `TenantRowLevelSecurityTest`; R2 filtro de `TenantScopedJdbc` → `TenantScopedJdbcTest`; R3 `set_config(..., false)` → `TenantPoolIsolationTest`; R4 `NO FORCE` → `TenantCatalogGuardTest`; R5 semilla en `db/migration` → `DefaultProfileTest`.

## Sesiones
- Una sesión con escritura por repo; worktree por sesión. Todo prompt trae SESIÓN + PRECONDICIONES; si falla una, parar y reportar.

## CI
- Runner **self-hosted** con etiqueta `pistaberta` (uno por repo, en el Mac de Pe, como el resto del portfolio). El job elige runner con `runs-on: ${{ vars.CI_RUNNER || 'ubuntu-latest' }}` y la variable de repo `CI_RUNNER=pistaberta`; borrar la variable vuelve a `ubuntu-latest`.
- CI ejecuta `mvn -B clean verify`: el runner persiste entre runs y sin `clean` un `target/` viejo (clases o migraciones Flyway ya borradas) da falsos verdes. Es la misma razón de la regla de mutaciones.
- Los tests de integración usan Testcontainers: el runner necesita Docker corriendo y `docker-credential-anon` en el PATH con `credsStore: anon` en su `DOCKER_CONFIG` (sin él el servicio cae con «keychain cannot be accessed»).
