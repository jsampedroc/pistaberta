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

## Reservas (D-007..D-009, `docs/SDD-reservas.md`)
- **Invariante de no-solapamiento en DOS capas, ninguna sustituye a la otra:** dominio (`DisponibilidadePista`, fallo rápido con `SlotNonDisponible`) y BD (`reserva_sen_solape`: `EXCLUDE USING gist (tenant_id WITH =, pista_id WITH =, tstzrange(inicio, fin) WITH &&) WHERE (estado = 'confirmada')`). Parcial: cancelar libera la franxa. Intervalo semiabierto `[inicio, fin)`: las adyacentes no solapan. `CHECK (fin > inicio)`; la `Franxa` se normaliza a microsegundos.
- Los errores de la BD no salen crudos: `JdbcReservaRepository` traduce `23P01` + `reserva_sen_solape` a `SlotNonDisponible` y `23503` + `reserva_pista_fk` a `PistaNonExiste`. Los nombres de constraint son **literales** en `V3` y en `JdbcReservaRepository` (`CONSTRAINT_SOLAPE`, `CONSTRAINT_PISTA`; los tests usan esa constante): no hay una fuente única. Una divergencia la cazan las mutaciones de RV2/RV6a (solape) y RV6b (FK).
- La reserva se cancela, no se borra: la app no tiene `DELETE` sobre `reserva` y solo `SELECT` sobre `pista`. `GRANT` explícito en cada migración. **`UPDATE` solo sobre la columna `estado`** (`GRANT UPDATE (estado)`): la franxa, la pista y el tenant son inmutables para la app.
- **`cancelada` es terminal:** el trigger `reserva_non_reabrir` prohíbe `cancelada → confirmada` (`23514`, constraint `reserva_non_reabrir`) para cualquier rol y aunque la franxa esté libre; para volver a reservar se crea una reserva nueva. Los tests de transición dejan la franxa libre (si no, la exclusion constraint lo frenaría y el test sería mudo).
- PK de `reserva` compuesta `(tenant_id, id)`: un id de otro tenant no colisiona ni revela existencia.
- Los casos de uso (`ReservarPista`, `CancelarReserva`) son Java plano en `application` y se ejecutan dentro del puerto `Transaccion` (`TransaccionSpring` sobre `TenantAwareTransactionManager`).
- **Mutaciones por TIER** (cada una aislada, `mvn clean verify`): dominio (`comprobarLibre` sin efecto, `solapaCon` con `<=`, `Franxa` sin normalizar) vs BD (quitar la constraint tumba RV2, RV4 y RV6a como tres facetas de la misma barrera: sin violación real no hay excepción que traducir; la traducción conserva su mutación propia, "adaptador sin traducir", que la aísla; `GRANT UPDATE` de tabla entera, sin el trigger y PK `(id)` global, cada una tumba solo su test; `'[]'`, constraint sin `WHERE`, FK sin `tenant_id`, `GRANT DELETE`, `NO FORCE`) vs adaptador (sin traducción).
- **RV4 (concurrencia) solo cuenta con el aserto de `pg_stat_activity`** (`wait_event_type = 'Lock'`) que prueba que la 2ª transacción queda BLOQUEADA. Un RV4 verde sin ese aserto es verde mudo.
- Identificadores en gallego y ASCII (`Franxa`, `SlotNonDisponible`, `PistaNonExiste`, `ReservaXaCancelada`); la `ñ` solo en documentación y strings de usuario.
- Tests de integración: cada clase con su `@DynamicPropertySource` crea su propio contexto; el perfil `test` limita el pool de Hikari (`application-test.yml`) para no agotar `max_connections` del Postgres de test.

## Sesiones
- Una sesión con escritura por repo; worktree por sesión. Todo prompt trae SESIÓN + PRECONDICIONES; si falla una, parar y reportar.

## CI
- Runner **self-hosted** con etiqueta `pistaberta` (uno por repo, en el Mac de Pe, como el resto del portfolio). El job elige runner con `runs-on: ${{ vars.CI_RUNNER || 'ubuntu-latest' }}` y la variable de repo `CI_RUNNER=pistaberta`; borrar la variable vuelve a `ubuntu-latest`.
- CI ejecuta `mvn -B clean verify`: el runner persiste entre runs y sin `clean` un `target/` viejo (clases o migraciones Flyway ya borradas) da falsos verdes. Es la misma razón de la regla de mutaciones.
- Los tests de integración usan Testcontainers: el runner necesita Docker corriendo y `docker-credential-anon` en el PATH con `credsStore: anon` en su `DOCKER_CONFIG` (sin él el servicio cae con «keychain cannot be accessed»).
