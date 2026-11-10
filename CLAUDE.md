# CLAUDE.md — PistaAberta

Plataforma libre de reserva de instalacións deportivas municipais (candidatura I Certame GOBTEC). Licencia EUPL-1.2.

<!-- metodo: v2 -->
Rama de integración: `main`

## Rama de integración
- **Base: `main`**. Repo público `jsampedroc/pistaberta` (candidatura GOBTEC).
- Ramas `feat/<...>` / `chore/<...>`; PRs independientes desde `origin/main` limpio, **nunca apilados**.
- Nunca merge a `main` sin validación del/de la mantenedor(a). Lee y mide contra `origin/main` tras `git fetch`.

## Método v2
Reglas globales en `~/.claude/CLAUDE.md`; el porqué, en dev-knowledge/claude-code/metodo.md.
- Base: `main`. PRs independientes desde base limpia; un worktree por sesión con `git worktree add -b <rama> ../pistaberta-<nombre> origin/main` (este repo no necesita `scripts/worktree.sh`: no hay ficheros fuera de git que copiar, las credenciales de `dev` van en `docker-compose.yml` y `application-dev.yml`).
- Decisiones: `docs/decisiones.md`. Solo se implementa lo ACEPTADO; se apunta en el mismo PR.
- Origen en cada PR (issue / D-xxx / invariante). Plantilla en `.github/pull_request_template.md`. El gate `metodo.yml` de ci-shared no corre aquí (repo público, ci-shared privado; ver #21): hasta resolverlo, la línea `Origen:` y la marca de este fichero se comprueban con `scripts/metodo-check.sh` de ci-shared.
- Local antes de PR: `mvn -B verify` (compila, comprueba `maven-enforcer` y ejecuta los tests; no hay paso de typecheck aparte: el compilador de Maven lo cubre).
- Mutaciones: a mano, restaurando el código tras cada una y siempre con `mvn clean verify`; aisladas, una regla por mutación (listas por tier en las secciones de abajo). No hay herramienta de mutación automática.
- Keywords de cierre solo en cuerpo del PR y última línea del squash.
- Revisión adversarial (`/revision-adversarial <PR>`) si el PR toca dinero, seguridad o esquema: en este repo, todo PR con migración, RLS, `GRANT` o tokens.
- No aplican aquí: `npm`/`tsc` (no hay frontend en el repo) ni `scripts/worktree.sh` (ver arriba).

## Stack y arquitectura
- Java · Spring Boot · Maven multi-módulo · PostgreSQL + PostGIS (versiones en `pom.xml` y `docker-compose.yml`).
- Hexagonal/DDD: `pistaberta-domain` ← `pistaberta-application` ← `pistaberta-infrastructure`.
- Domain y application NO dependen de Spring ni JPA (maven-enforcer lo hace fallar en `verify`).

## Convenciones
- **Flyway-first**: el esquema solo cambia por migraciones `V<n>__*.sql`; nunca `ddl-auto=update`; nunca editar una migración ya mergeada.
- **Testcontainers** con la imagen `postgis/postgis` de `docker-compose.yml` para integración; sin BD simulada. Versión de Testcontainers fijada en `pom.xml` (`testcontainers.version`; Docker >= 29).
- **Un verde no puede ser mudo**: cada regla con un test que muerde y su mutación aislada; prohibir se verifica.
- **Mutaciones siempre con `mvn clean verify`, nunca `mvn verify`**: sin `clean`, `target/classes` conserva clases/recursos ya borrados (p.ej. migraciones Flyway) y da un falso verde. Un verde sin `clean` no prueba que la mutación muerda.
- Al borrar/mover un recurso o export para una mutación, confirma que el build cae tras `clean`; si no cae, la mutación no está probando nada.
- Bucle local: `mvn -B verify` en verde antes de abrir PR. CI = `mvn -B clean verify` (ver sección CI).
- Keywords de cierre (`closes #n`) solo en el cuerpo del PR / última línea del squash, nunca en commits.
- Cada PR nombra su origen (issue, D-xxx, invariante). Decisiones de dominio ACEPTADAS se apuntan en `docs/decisiones.md` en el mismo PR.
- Dominio nuevo (multi-tenant, reservas): **spec aprobada primero**, después código.

## Multi-tenant (D-003..D-006, `docs/SDD-multi-tenant.md`)
- Tenant = concello. Aislamiento por `tenant_id` + filtro aplicativo + RLS de Postgres. **Los dos son obligatorios**: ninguna capa sustituye a la otra.
- Toda tabla de datos nueva: `tenant_id uuid not null`, `ENABLE` + `FORCE ROW LEVEL SECURITY` y policy con `app_tenant_id()`, índice que empiece por `tenant_id`, unicidades y FKs con `tenant_id`, y `GRANT` DML explícito a `pistaberta_app` en su migración (no hay `ALTER DEFAULT PRIVILEGES`). `TenantCatalogGuardTest` revisa TODAS las tablas de `public`: o están en su allowlist de catálogos globales (añadir una exige tocar el test) o fallan.
- **Todo acceso a datos va dentro de una transacción** (`@Transactional`): el tenant se fija con `set_config(..., true)` al empezar la transacción (`TenantAwareTransactionManager`). Fuera de transacción no hay tenant y la BD devuelve 0 filas (fail-closed). Prohibido el GUC de sesión.
- El acceso aplicativo pasa por `TenantScopedJdbc` (`query`/`insert`/`update`/`delete`: el filtro por tenant lo pone el componente; `tenant_id` no se puede reasignar; sin tenant lanza `TenantNotSetException`). Los fragmentos SQL son constantes y los valores van como parámetros: **RLS protege frente a filtros olvidados, no frente a inyección SQL** (el rol de la app puede fijar el GUC). Async e hilos virtuales con acceso a datos: fuera hasta su spec.
- La app se conecta como `pistaberta_app` (sin superuser ni BYPASSRLS); Flyway migra con el owner (`DB_MIGRATION_USER`). Nunca conectar la app como owner. Defensas: la migración de aislamiento (`db/migration/*tenant_isolation*`) falla si el rol existe con SUPERUSER/BYPASSRLS y `AppRoleGuard` impide arrancar con un rol que se salte RLS. Las contraseñas no tienen valor por defecto fuera del perfil `dev`.
- `TenantFilter` cubre **toda** request (deny by default), no solo `/api/**`: filtrar por prefijo de ruta se saltaba con `/api;x=1/...`.
- Resolver de tenant por header solo en perfiles `dev`/`test`; prod no tiene resolver hasta la spec de auth. Semilla (`db/seed`) solo dev/test; la tabla de prueba (`db/test`) solo test.
- Mutaciones de aislamiento (una a una, `mvn clean verify`): R1 policy RLS → `TenantRowLevelSecurityTest`; R2 filtro de `TenantScopedJdbc` → `TenantScopedJdbcTest`; R3 `set_config(..., false)` → `TenantPoolIsolationTest`; R4 `NO FORCE` → `TenantCatalogGuardTest`; R5 semilla en `db/migration` → `DefaultProfileTest`.

## Reservas (D-007..D-010, `docs/SDD-reservas.md`)
- **Invariante de no-solapamiento en DOS capas, ninguna sustituye a la otra:** dominio (`DisponibilidadePista`, fallo rápido con `SlotNonDisponible`) y BD (`reserva_sen_solape`: `EXCLUDE USING gist (tenant_id WITH =, pista_id WITH =, tstzrange(inicio, fin) WITH &&) WHERE (estado = 'confirmada')`). Parcial: cancelar libera la franxa. Intervalo semiabierto `[inicio, fin)`: las adyacentes no solapan. `CHECK (fin > inicio)`; la `Franxa` se normaliza a microsegundos.
- Los errores de la BD no salen crudos: `JdbcReservaRepository` traduce `23P01` + `reserva_sen_solape` a `SlotNonDisponible` y `23503` + `reserva_pista_fk` a `PistaNonExiste`. Los nombres de constraint son **literales** en la migración de reservas (`db/migration/*pista_reserva*`) y en `JdbcReservaRepository` (`CONSTRAINT_SOLAPE`, `CONSTRAINT_PISTA`; los tests usan esa constante): no hay una fuente única. Una divergencia la cazan las mutaciones de RV2/RV6a (solape) y RV6b (FK).
- La reserva se cancela, no se borra: la app no tiene `DELETE` sobre `reserva` y solo `SELECT` sobre `pista`. `GRANT` explícito en cada migración. **`UPDATE` solo sobre la columna `estado`** (`GRANT UPDATE (estado)`): la franxa, la pista y el tenant son inmutables para la app.
- **`cancelada` es terminal:** el trigger `reserva_non_reabrir` prohíbe `cancelada → confirmada` (`23514`, constraint `reserva_non_reabrir`) para cualquier rol y aunque la franxa esté libre; para volver a reservar se crea una reserva nueva. Los tests de transición dejan la franxa libre (si no, la exclusion constraint lo frenaría y el test sería mudo).
- PK de `reserva` compuesta `(tenant_id, id)`: un id de otro tenant no colisiona ni revela existencia.
- Los casos de uso (`ReservarPista`, `CancelarReserva`) son Java plano en `application` y se ejecutan dentro del puerto `Transaccion` (`TransaccionSpring` sobre `TenantAwareTransactionManager`).
- **Mutaciones por TIER** (cada mutación aísla su tier/regla, no un único test: puede tumbar varios tests de la MISMA regla, p. ej. `GRANT UPDATE` de tabla entera tumba 2; cada una aislada, `mvn clean verify`): dominio (`comprobarLibre` sin efecto, `solapaCon` con `<=`, `Franxa` sin normalizar) vs BD (quitar la constraint tumba RV2, RV4 y RV6a como tres facetas de la misma barrera: sin violación real no hay excepción que traducir; la traducción conserva su mutación propia, "adaptador sin traducir", que la aísla; `GRANT UPDATE` de tabla entera, sin el trigger y PK `(id)` global, cada una tumba solo los tests de su regla; `'[]'`, constraint sin `WHERE`, FK sin `tenant_id`, `GRANT DELETE`, `NO FORCE`) vs adaptador (sin traducción).
- **RV4 (concurrencia) solo cuenta con el aserto de `pg_stat_activity`** (`wait_event_type = 'Lock'`) que prueba que la 2ª transacción queda BLOQUEADA. Un RV4 verde sin ese aserto es verde mudo.
- Identificadores en gallego y ASCII (`Franxa`, `SlotNonDisponible`, `PistaNonExiste`, `ReservaXaCancelada`); la `ñ` solo en documentación y strings de usuario.
- Tests de integración: cada clase con su `@DynamicPropertySource` crea su propio contexto; el perfil `test` limita el pool de Hikari (`application-test.yml`) para no agotar `max_connections` del Postgres de test.

## Extras: acceso por token, calendario, QR y analítica (D-011..D-016, `docs/SDD-extras.md`)
- **Token opaco por reserva, sin cuentas (D-011).** 256 bits de `SecureRandom` (`GeneradorTokenSeguro`), Base64URL de 43 caracteres. **En claro solo en la respuesta del `POST` de reservar** (`Cache-Control: no-store`); en BD solo su SHA-256 en `reserva_acceso` (tabla aparte: `reserva` sigue inmutable, D-010). `TokenAcceso.toString()` nunca revela el valor: no escribir el token en logs.
- **Reserva y acceso en la MISMA transacción** (`ReservarPistaConAcceso` sobre el puerto `Transaccion`): sin reserva no hay token y al revés.
- `reserva_acceso` (`db/migration/*reserva_acceso*`): PK `(tenant_id, reserva_id)`, FK compuesta a `reserva`, `UNIQUE (tenant_id, token_hash)` (por tenant, no global), `CHECK (octet_length = 32)`, RLS + FORCE y `GRANT SELECT, INSERT` solo (los tokens son inmutables y no se borran). `TestPostgres.resetReservas` trunca `reserva_acceso` antes que `reserva` (la FK lo exige).
- **API mínima (D-012):** `POST /api/pistas/{id}/reservas` y `POST /api/reservas/{id}/cancelar`, solo invocan los casos de uso; errores de los casos de uso y de MVC como `application/problem+json` con textos fijos (`ProblemasHttp`: nunca SQL, trazas ni ids ajenos). Los del `TenantFilter` (400 sin tenant, 404 desconocido, 401 en prod) salen **sin cuerpo**: ocurren antes de MVC (`sendError`). Por cabecera, **solo dev/test**: en prod no hay resolver y fallan cerrados (401) hasta la spec de auth. `@PathVariable` con nombre explícito: el proyecto no compila con `-parameters`.
- Pendiente (PR-B, PR-C): rutas con token por slug en la ruta (D-014), validación, `.ics`, QR y analítica.
- **Rango de franxas (guarda explícita en `JdbcReservaRepository`):** `[1900-01-01, 9999-12-31T23:59:59Z]`, si no `FranxaInvalida` (400). Hace falta porque pgjdbc convierte en silencio las fechas muy anteriores en `-infinity` (se guardaba una reserva ilimitada por el pasado, con 201) y Postgres rechaza las posteriores al año 294276 con un error crudo (500). La respuesta del `POST` devuelve la franxa **persistida** (microsegundos), no lo que envió el cliente. `ReservaCreada.toString()` no imprime el token.
- **Mutaciones PR-A** (una a una, `mvn clean verify`): hash truncado del token → `TokenAccesoTest`/`ReservarPistaConAccesoIntegracionTest`; acceso fuera de la transacción → `ReservarPistaConAccesoTest`/`...IntegracionTest`; policy permisiva → `ReservaAccesoEsquemaTest`; `NO FORCE` → `TenantCatalogGuardTest`; `GRANT UPDATE`, `GRANT DELETE`, `UNIQUE` global, sin FK y sin `CHECK` → `ReservaAccesoEsquemaTest`; `SlotNonDisponible` sin traducir, sin `no-store` → `ReservaApiTest`; resolver por cabecera en prod → `DefaultProfileTest`; `toString` que revela el token → `TokenAccesoTest`; generador sin aleatoriedad → `GeneradorTokenSeguroTest`; sin la guarda de rango → `JdbcReservaRepositoryTest`/`ReservaApiTest`; la respuesta devuelve lo enviado en vez de lo persistido → `ReservaApiTest`; `ReservaCreada` sin `toString` propio → `ReservaApiTest`; cada handler de `ProblemasHttp` sin traducir → `ReservaApiTest`.

## CI
- Runner **self-hosted** con etiqueta `pistaberta` (uno por repo). El job elige runner con `runs-on: ${{ vars.CI_RUNNER || 'ubuntu-latest' }}` y la variable de repo `CI_RUNNER=pistaberta`; borrar la variable vuelve a `ubuntu-latest`.
- CI ejecuta `mvn -B clean verify`: el runner persiste entre runs y sin `clean` un `target/` viejo (clases o migraciones Flyway ya borradas) da falsos verdes. Es la misma razón de la regla de mutaciones.
- Los tests de integración usan Testcontainers: el runner necesita Docker corriendo y `docker-credential-anon` en el PATH con `credsStore: anon` en su `DOCKER_CONFIG` (sin él el servicio cae con «keychain cannot be accessed»).
