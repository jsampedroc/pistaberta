# SDD — Reservas: invariante de no-solapamiento

Estado: **APROBADA por Pe (gate resuelto el 2026-10-30)** · §8 Plan completo · sin código ni migraciones en este PR; la implementación (`feat/reservas`) empieza tras el visto de Pe al PR #5.
Normativo: `CLAUDE.md`, `docs/SDD-multi-tenant.md`, `docs/decisiones.md` (D-003..D-009 aceptadas).

## 0. Meta
- **Proyecto:** PistaAberta (I Certame GOBTEC) · repo `jsampedroc/pistaberta`
- **Fecha:** 2026-10-30
- **Tipo:** módulo (primer módulo de dominio sobre el fundamento multi-tenant)
- **Target:** `origin/main` vía `feat/reservas` (PR de implementación aparte, autónomo, sin stacking)
- **Fichero:** `docs/SDD-reservas.md`
- **Aterrizado contra `origin/main@adaf4de`** (#1 bootstrap, #2 spec multi-tenant, #3 multi-tenant, #4 CI self-hosted):
  - Módulos `pistaberta-domain` ← `pistaberta-application` ← `pistaberta-infrastructure`; en `domain` y `application` solo existe el paquete `tenant` (`TenantId`, `TenantContext`, `TenantNotSetException`).
  - Migraciones: `pistaberta-infrastructure/src/main/resources/db/migration/V1__init_postgis_and_version.sql`, `V2__tenant_isolation.sql` (tabla `tenant`, `app_tenant_id()`, rol `pistaberta_app`, **sin** `ALTER DEFAULT PRIVILEGES`: cada tabla nueva concede su DML de forma explícita).
  - Acceso a datos: `pistaberta-infrastructure/.../tenant/TenantScopedJdbc.java` (`query`/`insert`/`update`/`delete`; `tenant_id` lo pone el componente y no se reasigna), `TenantAwareTransactionManager.java` (GUC `set_config(..., true)` en `doBegin`), `AppRoleGuard.java`.
  - Guard C6: `.../tenant/TenantCatalogGuardTest.java` revisa **todas** las tablas de `public` (allowlist `tenant`, `app_version`, `flyway_schema_history`, `spatial_ref_sys`): `pista` y `reserva` entrarán en él sin tocar la allowlist.
  - Tests: `TestPostgres.java` (contenedor compartido `postgis/postgis:16-3.4`, conexión superuser para preparar datos y `pistaberta_app` para probar RLS), perfiles `test`/`dev`, `db/test/R__tenant_probe.sql` (modelo de tabla de test).

### 0.1 Hechos medidos para esta spec (Postgres 16 / PostGIS 3.4, contenedor desechable, 2026-10-30)
| Comprobación | Resultado |
|---|---|
| `btree_gist` disponible y `trusted` | sí, `trusted=true` (la crea el dueño de la BD, sin superuser) |
| `EXCLUDE USING gist (tenant_id WITH =, pista_id WITH =, tstzrange(inicio, fin) WITH &&) WHERE (estado = 'confirmada')` | válida con columnas `uuid` |
| Dos reservas solapadas | la 2ª falla con **SQLSTATE `23P01`** y `CONSTRAINT NAME: reserva_sen_solape` |
| Adyacentes (`fin` de una = `inicio` de otra) | **permitidas** (el rango por defecto es `[)`) |
| Cancelar y re-reservar la misma franxa | permitido (la constraint es parcial) |
| `fin = inicio` | **no lo frena la exclusion** (rango vacío); lo frena un `CHECK (fin > inicio)` |
| Otra pista, mismo horario | permitido |
| Concurrencia (dos transacciones, mismo hueco) | la 2ª **espera** a la 1ª: si esta hace `COMMIT` la 2ª falla `23P01`; si hace `ROLLBACK` la 2ª triunfa |

## 1. OBJETIVO
Una pista no puede tener dos reservas confirmadas con franxas que se solapen. La invariante se garantiza en **dos capas**, como el aislamiento multi-tenant: en el dominio (fallo rápido y mensaje claro) y en la BD con una exclusion constraint (única barrera que resiste a la concurrencia y a un bug de código). Este módulo ancla esa invariante con un modelo mínimo tenant-scoped de pista y reserva, sin el catálogo completo, para que el resto del dominio se construya sobre ella.

Lenguaje ubicuo (gl/es):

| Término | Significado |
|---|---|
| **Pista** (espazo) | Recurso reservable de un concello. Aquí solo `id`, `tenant_id`, `nome`. |
| **Reserva** | Ocupación de una pista durante una franxa. Estados `confirmada` y `cancelada`. |
| **Franxa** (slot) | Intervalo semiabierto `[inicio, fin)` en instantes absolutos. |
| **Solape** | Dos franxas de la misma pista que comparten algún instante. Adyacentes **no** solapan. |
| **SlotNonDisponible** | Error de dominio: la franxa pedida choca con una reserva confirmada. |

## 2. QUÉ
### 2.1 In-scope (criterios verificables)
| ID | Criterio |
|---|---|
| RV1 | **Dominio:** dos reservas confirmadas solapadas en la misma pista → la 2ª se rechaza con `SlotNonDisponible` (sin tocar la BD). |
| RV2 | **BD:** insertar dos reservas confirmadas solapadas saltándose el dominio → la exclusion constraint rechaza la 2ª (`23P01`). |
| RV3 | **Borde:** reservas adyacentes (`fin` = `inicio`) **no** solapan, en dominio y en BD. |
| RV4 | **Concurrencia:** dos inserts solapados concurrentes → exactamente uno triunfa; el otro falla `23P01` (la BD serializa). |
| RV5 | **Cancelación libera:** cancelar una reserva confirmada y re-reservar la misma franxa → permitido (constraint parcial). |
| RV6 | **Traducción:** el adaptador convierte `23P01` en `SlotNonDisponible` y `23503` (pista inexistente en el tenant) en `PistaNonExiste`; nunca sale SQL crudo ni `DataIntegrityViolationException` del caso de uso. |
| RV7 | **Multi-tenant (C6):** `pista` y `reserva` con `tenant_id NOT NULL`, RLS + FORCE + policy e índice que empiece por `tenant_id`; una reserva no puede apuntar a la pista de otro tenant (FK compuesta). |
| RV8 | **Mínimo privilegio:** la app solo `SELECT` en `pista`; `SELECT, INSERT` y `UPDATE (estado)` (sin `DELETE`) en `reserva`; cancelar es un cambio de estado, no un borrado; `cancelada → confirmada` prohibido por trigger. |
| RV9 | **Integridad de la franxa:** `fin > inicio` (dominio y `CHECK`); la franxa se normaliza a microsegundos para que dominio y BD coincidan en el borde. |
| RV10 | `domain` y `application` siguen sin Spring ni JPA (enforcer verde); `V1` y `V2` no se tocan. |

### 2.2 Out-of-scope (explícito)
- **Catálogo completo:** deportes, horarios y normas de uso, ubicación PostGIS, tarifas. Es otra spec.
- Usuarios / titular de la reserva, autenticación y autorización (la reserva no lleva solicitante hasta la spec de auth).
- API REST, UI y notificaciones: este módulo se ejerce por casos de uso y tests, sin endpoints.
- Reglas de negocio de calendario: antelación mínima/máxima, duración mínima/máxima o granularidad de franxa, reservas en el pasado (necesitan reloj), horario de apertura.
- Reservas periódicas, lista de espera, pagos, overbooking.
- Consultas de disponibilidad (huecos libres) y listados.
- Alta/baja de pistas (las crea la migración o el test; sin camino de escritura de la app).
- **Recursos jerárquicos** (p. ej. una pista de fútbol 11 que se divide en dos de fútbol 7): la constraint no cubre conflictos entre pistas relacionadas. Riesgo conocido R-3.

## 3. CÓMO
- **Stack:** Java 21 · Spring Boot 3.3 · PostgreSQL 16 + PostGIS · Flyway · Testcontainers. Multi-tenancy: **sí** (heredada, sin cambios). Auth: ninguna. Sin Kafka/Redis/MinIO.
- **Hexagonal** (`domain` y `application` sin Spring/JPA):
  - `pistaberta-domain`, paquete `org.pistaberta.domain.reserva`:
    - `Franxa` (record `Instant inicio, fin`; exige `fin > inicio`, normaliza a microsegundos, `solapaCon(Franxa)` con semántica `[)`).
    - `Reserva` (aggregate: `ReservaId`, `PistaId`, `Franxa`, `EstadoReserva`; fábrica `Reserva.confirmar(...)`; `cancelar()`).
    - `EstadoReserva` (`CONFIRMADA`, `CANCELADA`), `PistaId`, `ReservaId`.
    - Servicio de dominio `DisponibilidadePista.comprobarLibre(Franxa, Collection<Reserva> activas)`.
    - Errores de dominio: `SlotNonDisponible`, `PistaNonExiste`, `ReservaXaCancelada`.
    - La reserva **no conoce el tenant**: es una preocupación de infraestructura (`TenantContext`).
  - `pistaberta-application`, paquete `org.pistaberta.application.reserva`:
    - Casos de uso `ReservarPista` y `CancelarReserva`.
    - Puertos `ReservaRepository` (`activasSolapadas(PistaId, Franxa)`, `gardar`, `buscar`, `actualizar`) y `Transaccion` (`executar(Supplier<T>)`).
  - `pistaberta-infrastructure`:
    - `JdbcReservaRepository` sobre `TenantScopedJdbc` (traduce `23P01`/`23503` a errores de dominio) y adaptador `Transaccion` con `TransactionTemplate` sobre `TenantAwareTransactionManager`.
    - Migración `V3__pista_reserva.sql`.
- **Flujo de `ReservarPista`** (todo dentro de `Transaccion`, como exige `CLAUDE.md`): (1) leer las reservas activas solapadas de la pista; (2) `DisponibilidadePista.comprobarLibre` → fallo rápido con mensaje claro; (3) `Reserva.confirmar` y `gardar`. La BD es la última palabra: si otra transacción se adelanta entre (1) y (3), el `INSERT` falla `23P01` y el adaptador lo convierte en el mismo `SlotNonDisponible`. El fallo aborta la transacción (rollback), sin reintentos automáticos.
- **Dos capas = dos errores del mismo tipo:** el llamante ve siempre `SlotNonDisponible`, sea quien sea quien detecte el choque.
- **Decisión sobre `tenant_id` en la constraint (D-008, propuesta):** la exclusion lleva `tenant_id WITH =` además de `pista_id`. Semánticamente es redundante (la pista pertenece a un tenant y la FK compuesta lo fuerza), pero (a) las comprobaciones de unicidad/exclusión **no pasan por RLS**, así que sin `tenant_id` un conflicto podría cruzar tenants si la FK faltara; (b) hace que el índice GiST que respalda la constraint empiece por `tenant_id`, como pide la convención de `CLAUDE.md`. Coste: ninguno medible.
- **Alternativas evaluadas para la invariante (D-007):**

| Alternativa | Valoración |
|---|---|
| Solo check en el dominio | No resiste a la concurrencia ni a un bug: dos transacciones pasan el check a la vez. Descartada. |
| Solo constraint en BD | Funciona, pero el error llega tarde y como SQL crudo; el dominio no expresa la regla. Descartada. |
| Bloqueo pesimista (`SELECT ... FOR UPDATE` sobre la pista) o aislamiento `SERIALIZABLE` | Más contención y reintentos; la constraint es declarativa y más barata. Descartada. |
| **Dominio + exclusion constraint parcial** | Fallo rápido y barrera atómica. **Propuesta.** |

## 4. MODELO DE DATOS
Migración **`V3__pista_reserva.sql`** (descrita, no implementada; `V1` y `V2` no se tocan):

```sql
CREATE EXTENSION IF NOT EXISTS btree_gist;            -- trusted: la crea el dueño de la BD

CREATE TABLE pista (
    id         uuid PRIMARY KEY,
    tenant_id  uuid NOT NULL REFERENCES tenant (id),
    nome       text NOT NULL,
    CONSTRAINT pista_tenant_id_uq UNIQUE (tenant_id, id)      -- destino de la FK compuesta; índice que empieza por tenant_id
);

CREATE TABLE reserva (
    id         uuid NOT NULL,
    tenant_id  uuid NOT NULL REFERENCES tenant (id),
    pista_id   uuid NOT NULL,
    inicio     timestamptz NOT NULL,
    fin        timestamptz NOT NULL,
    estado     text NOT NULL,
    CONSTRAINT reserva_pkey       PRIMARY KEY (tenant_id, id),     -- compuesta (corrección F2, ver nota)
    CONSTRAINT reserva_pista_fk   FOREIGN KEY (tenant_id, pista_id) REFERENCES pista (tenant_id, id),
    CONSTRAINT reserva_estado_ck  CHECK (estado IN ('confirmada', 'cancelada')),
    CONSTRAINT reserva_franxa_ck  CHECK (fin > inicio),
    CONSTRAINT reserva_sen_solape EXCLUDE USING gist
        (tenant_id WITH =, pista_id WITH =, tstzrange(inicio, fin) WITH &&)
        WHERE (estado = 'confirmada')
);
CREATE INDEX reserva_tenant_pista_inicio_idx ON reserva (tenant_id, pista_id, inicio);

-- RLS en ambas (mismo patrón que tenant_probe): ENABLE + FORCE + policy
--   USING (tenant_id = app_tenant_id()) WITH CHECK (tenant_id = app_tenant_id())
GRANT SELECT ON pista TO pistaberta_app;
GRANT SELECT, INSERT ON reserva TO pistaberta_app;           -- sin DELETE
GRANT UPDATE (estado) ON reserva TO pistaberta_app;         -- solo estado (corrección F1)
-- Trigger BEFORE UPDATE OF estado ... WHEN (OLD.estado = 'cancelada' AND NEW.estado = 'confirmada')
--   → RAISE check_violation (23514), constraint reserva_non_reabrir (corrección F1)
```

**Correcciones tras la 1ª revisión adversarial del PR #6 (visto de Pe):**
- **F2 · PK compuesta.** Esta spec pedía `PRIMARY KEY (id)` global; la convención de `CLAUDE.md` (unicidades con `tenant_id`) manda. Con PK global, insertar un `id` que pertenece a otro tenant fallaba con `23505` y revelaba su existencia. La FK compuesta no cambia.
- **F1 · mínimo privilegio y terminal.** La app solo puede `UPDATE (estado)`; `cancelada → confirmada` lo prohíbe un trigger (se crea una reserva nueva). Refina D-008 (ver D-010).
- **RV6a** es la tercera faceta de la barrera de BD: quitar `reserva_sen_solape` tumba RV2, RV4 y RV6a. No se fabrica una `PSQLException` sintética.

- `tstzrange(inicio, fin)` usa el límite por defecto `[)`: dos franxas adyacentes no solapan.
- La constraint es **parcial**: solo cuenta `estado = 'confirmada'`; las canceladas se conservan como histórico y no bloquean.
- `estado` es `text` + `CHECK` (no enum de Postgres): añadir estados es un `ALTER` de la constraint, no de un tipo.
- Los ids los genera la aplicación (`ReservaId`), no la BD, porque el dominio crea el aggregate antes de persistirlo.
- Nombres de constraints: constantes compartidas por la migración y el adaptador (`reserva_sen_solape`, `reserva_pista_fk`).
- Las comprobaciones de FK y de exclusión se hacen sin RLS (documentación de Postgres): por eso la FK es **compuesta** `(tenant_id, pista_id)`; apuntar a la pista de otro tenant falla igual que apuntar a una pista inexistente (`23503`), sin revelar que existe.
- Semilla: ninguna (los tests crean pistas con la conexión superuser; la app no escribe en `pista`).
- `TenantCatalogGuardTest` exige RLS + FORCE + policy en las dos tablas; no hace falta tocar su allowlist.

## 5. CONTRATOS / API
### 5.1 Contratos
Sin endpoints REST en este módulo. El contrato son los puertos de `application`:

| Operación | Entrada | Resultado | Errores de dominio |
|---|---|---|---|
| `ReservarPista` | `PistaId`, `Franxa` | `Reserva` confirmada | `SlotNonDisponible`, `PistaNonExiste` |
| `CancelarReserva` | `ReservaId` | `Reserva` cancelada | `ReservaXaCancelada`, reserva inexistente en el tenant |

| Origen técnico | Error de dominio |
|---|---|
| `23P01` + constraint `reserva_sen_solape` | `SlotNonDisponible` |
| `23503` + constraint `reserva_pista_fk` | `PistaNonExiste` |
| otro | se propaga tal cual (no se oculta un fallo inesperado) |

### 5.2 Falsabilidad
Cada regla con su test que muerde y su mutación **aislada**, ejecutada con `mvn clean verify`; un verde sin `clean` no cuenta. Tras cada mutación se confirma que el build cae tras `clean`.

| Regla | Test que muerde | Mutación aislada | Rojo esperado |
|---|---|---|---|
| RV1 dominio | `DisponibilidadePistaTest`: dos solapadas, misma pista → `SlotNonDisponible` | `comprobarLibre` no hace nada | solo ese test (el de BD sigue verde) |
| RV2 BD | `ReservaExclusionConstraintTest`: dos solapadas insertadas por SQL saltándose el dominio → 2ª `23P01` | quitar `reserva_sen_solape` de `V3` | RV2, RV4 **y** RV6a (ver nota) |
| RV3a borde dominio | `FranxaTest`: `fin == inicio` ajeno no solapa | `solapaCon` con `<=` | solo ese test |
| RV3b borde BD | `ReservaExclusionConstraintTest`: adyacentes permitidas | rango `'[]'` en la constraint | solo ese test |
| RV4 concurrencia | `ReservaConcurrenciaTest`: tx1 inserta y no confirma; tx2 queda **bloqueada** (aserto obligatorio: `pg_stat_activity` con `wait_event_type = 'Lock'`); tx1 `COMMIT` → tx2 `23P01`; variante con `ROLLBACK` → tx2 triunfa; exactamente uno | quitar `reserva_sen_solape` (comparte mutación con RV2) | RV2, RV4 y RV6a |
| RV4-b aserto de bloqueo | `ReservaConcurrenciaTest` (mismo test): el aserto de `pg_stat_activity` | la 2ª inserción se lanza tras el `COMMIT` de la 1ª (sin bloqueo) | solo RV4 (RV2 verde) |
| RV5 cancelación | `ReservaCancelacionTest`: cancelar y re-reservar la misma franxa → permitido | constraint **no** parcial (quitar el `WHERE`) | solo ese test |
| RV6 traducción | `JdbcReservaRepositoryTest`: `gardar` dos veces saltando el check de dominio → `SlotNonDisponible`; pista de otro tenant → `PistaNonExiste` | el adaptador deja pasar la excepción de Spring | solo ese test |
| RV7 tenant | `ReservaTenantTest`: tenant A no ve ni afecta reservas de B con SQL crudo; FK compuesta rechaza pista de otro tenant | FK solo sobre `pista_id` | solo ese test |
| RV7 guard | `TenantCatalogGuardTest` (existente) | `NO FORCE` en `reserva` | solo el guard |
| RV8 privilegios | `ReservaPrivilegiosTest`: `has_table_privilege('pistaberta_app', 'reserva', 'DELETE')` es falso | `GRANT DELETE` | solo ese test |
| RV8 columna (F1) | `ReservaPrivilegiosTest`: `has_column_privilege` del rol app sobre `inicio`/`fin`/`pista_id`/`tenant_id`/`id` = falso y sobre `estado` = verdadero; el `UPDATE` de la franxa con el rol app da `42501` | `GRANT UPDATE ON reserva` (de tabla entera) | solo esos 2 tests |
| RV8 terminal (F1) | `ReservaTransicionTest`: `cancelada → confirmada` con la franxa **libre** → `23514` / `reserva_non_reabrir` | quitar el trigger | solo ese test |
| F2 PK compuesta | `ReservaTenantTest`: el tenant A inserta un `id` de reserva de B sin colisión (sin `23505`) | `PRIMARY KEY (id)` global | solo ese test |
| F3 nombres | RV2 (el test compara con `CONSTRAINT_SOLAPE`) y RV6a/RV6b | renombrar la constraint en `V3` | RV2 + RV6a (solape); RV6b + `ReservarPistaIntegracionTest` (FK) |
| RV9 franxa | `FranxaTest`: `fin <= inicio` rechazado; dos `Instant` que difieren en nanosegundos se normalizan igual que en BD | quitar la normalización | solo ese test |

**Separación de mutaciones por TIER (resuelto por Pe, punto 11):**
- **Tier dominio:** RV1 (`comprobarLibre` no hace nada), RV3a (`<=`) y RV9 (sin normalización) mutan código del dominio; solo caen sus tests de dominio.
- **Tier BD:** quitar `reserva_sen_solape` muta la barrera de la BD y tumba **RV2, RV4 y RV6a**, tres facetas de la misma barrera (no se pretende aislar RV4 ni RV6a de RV2 con otra mutación; RV6a cae porque sin violación real no hay excepción que traducir, y la traducción conserva su mutación propia, "adaptador sin traducir", que la aísla). No se fabrica una `PSQLException` sintética. El resto de mutaciones del tier BD (`'[]'`, sin `WHERE`, FK sin `tenant_id`, `GRANT DELETE`, `NO FORCE`) tumban cada una solo su test.
- **RV4 no es redundante:** su evidencia propia es el aserto sobre `pg_stat_activity` que prueba que la 2ª transacción queda **bloqueada** (la BD serializa). **Un RV4 verde sin ese aserto de bloqueo se considera verde mudo y no cuenta.** Se falsifica con la mutación de escenario "RV4-b": ejecutar la 2ª inserción solo después del `COMMIT` de la 1ª (sin bloqueo real) debe poner RV4 en rojo y dejar RV2 en verde.

**Medir antes de mitigar (orden obligatorio de la implementación):**
1. Con las tablas `pista`/`reserva` creadas **sin** la exclusion constraint, el test de solape en BD debe salir en **rojo**: los dos inserts pasan (la fuga existe). Solo entonces se añade la constraint.
2. Mismo orden para la concurrencia: sin constraint, los dos inserts concurrentes triunfan (2 filas).
3. Antes de dar RV4 por bueno, comprobar que la 2ª transacción está realmente **bloqueada** (no que simplemente va después), si no el test no demuestra que la BD serializa.
4. `EXPLAIN` de la consulta `activasSolapadas` con volumen (p. ej. 200.000 filas) para confirmar que usa el índice GiST antes de añadir ningún índice extra.
5. Confirmar que `btree_gist` está disponible en la imagen de test antes de escribir la migración (ya medido: sí).

## 6. CUÁNDO PARAR (DoD)
- RV1..RV10 verificados y `mvn -B clean verify` en verde (en el runner self-hosted y en local).
- Las mutaciones de §5.2 demostradas en rojo y revertidas, con la evidencia en el PR.
- `V3` aplica limpio sobre BD vacía y sobre el esquema de `V2`; `V1` y `V2` intactas.
- `TenantCatalogGuardTest` sigue en verde con `pista` y `reserva`.
- Decisiones D-007..D-009 ya ACEPTADAS en `docs/decisiones.md` (PR #5).
- Sin merge a `main` sin validación de Pe. `/revision-adversarial` antes del merge (toca esquema e integridad de datos).
- **NO pulir:** granularidad/duración de franxas, reloj y reglas de antelación, listados, rendimiento más allá del `EXPLAIN`, documentación de usuario.

## 7. RESTRICCIONES / no-gos
- Flyway-first: nunca `ddl-auto`; no editar migraciones ya mergeadas (`V1`, `V2`).
- Domain y application puros: sin Spring, JPA ni `java.sql`; el tiempo es `java.time.Instant`.
- Todo acceso a datos dentro de transacción y por `TenantScopedJdbc`; sin `SELECT ... FOR UPDATE` ni `SERIALIZABLE` como sustituto de la constraint.
- Sin `ALTER DEFAULT PRIVILEGES`: los `GRANT` de `V3` son explícitos y mínimos.
- La invariante nunca se confía a una sola capa; el dominio no sustituye a la constraint ni al revés.
- PR autónomo desde `origin/main`, sin stacking; este PR de spec es solo documentación.

### Riesgos
- **R-1 · `CREATE EXTENSION btree_gist` en Postgres gestionado:** la extensión es `trusted`, pero algunos hostings no dejan crear extensiones al migrador. Si pasa, ops la pre-aprovisiona y `IF NOT EXISTS` hace la migración idempotente.
- **R-2 · Contención en franxas muy demandadas:** la 2ª transacción espera a la 1ª; con mucha concurrencia sobre el mismo hueco hay esperas cortas, no deadlocks.
- **R-3 · Recursos jerárquicos:** una pista dividida en otras no queda cubierta; hará falta otro modelo de exclusión en la spec del catálogo.
- **R-4 · Precisión temporal:** Java tiene nanosegundos y Postgres microsegundos; sin normalizar, dominio y BD podrían discrepar en el borde (RV9).
- **R-5 · Reserva sin titular:** hasta la spec de auth no se sabe quién reservó; cancelar no tiene autorización.
- **R-6 · Zona horaria y DST:** se guardan instantes absolutos; mostrar o razonar en `Europe/Madrid` queda para la spec de normas y UI.

## 8. PLAN DE EJECUCIÓN
Implementación en `feat/reservas`, un solo PR autónomo desde `origin/main` (sin stacking). Orden obligatorio **medir antes de mitigar**: migración **sin** la constraint y tests en rojo (solape y concurrencia) antes de añadirla. `‖` = paralelizable. Una tarea sin origen sobra.

| ID | Tarea | Origen | Depende de |
|---|---|---|---|
| T1 | `V3__pista_reserva.sql` **sin** `reserva_sen_solape`: `btree_gist`, `pista`, `reserva` (CHECKs de estado y de franxa, FK compuesta, índice `(tenant_id, pista_id, inicio)`), RLS + FORCE + policy en ambas, `GRANT` mínimos. | RV7, RV8, RV9, D-008, §4 | — |
| T2 | Helpers de test en `TestPostgres` (alta de pistas con el superuser, inserción cruda de reservas, rol `pistaberta_app`). ‖ T1, T5 | §5.2 | — |
| T3 | **Medición en rojo:** `ReservaExclusionConstraintTest` (solape) y `ReservaConcurrenciaTest` (con el aserto de bloqueo de `pg_stat_activity`) contra el esquema de T1 → ambos **rojos** (los dos inserts pasan, 2 filas, la 2ª no se bloquea). Se guarda la salida como evidencia. | §5.2 medir (1)-(3), RV2, RV4 | T1, T2 |
| T4 | Añadir `reserva_sen_solape` a `V3` (`EXCLUDE USING gist (tenant_id WITH =, pista_id WITH =, tstzrange(inicio, fin) WITH &&) WHERE (estado = 'confirmada')`) → T3 pasa a **verde**. | RV2, RV4, D-007, D-008 | T3 |
| T5 | Dominio puro `org.pistaberta.domain.reserva`: `Franxa` (microsegundos, `[)`), `Reserva`, `EstadoReserva`, ids, `DisponibilidadePista`, errores (`SlotNonDisponible`, `PistaNonExiste`, `ReservaXaCancelada`) + tests (`FranxaTest`, `DisponibilidadePistaTest`, `ReservaTest`). Identificadores ASCII. ‖ T1, T2 | RV1, RV3a, RV9, RV10 | — |
| T6 | Tests de BD restantes: adyacentes permitidas (RV3b), cancelar y re-reservar (`ReservaCancelacionTest`, RV5), aislamiento de tenant y FK compuesta (`ReservaTenantTest`, RV7), privilegios (`ReservaPrivilegiosTest`, RV8). ‖ T7, T8 | RV3b, RV5, RV7, RV8 | T4 |
| T7 | `application.reserva`: puertos `ReservaRepository` y `Transaccion`, casos de uso `ReservarPista` y `CancelarReserva`, con tests sobre fakes en memoria (sin Spring). ‖ T6, T8 | RV1, RV10, D-009 | T5 |
| T8 | Medición con volumen: `EXPLAIN` de `activasSolapadas` con 200.000 filas; confirmar que usa el índice GiST antes de añadir ningún índice extra. ‖ T6, T7 | §5.2 medir (4) | T4 |
| T9 | Infraestructura: `JdbcReservaRepository` sobre `TenantScopedJdbc`, adaptador `Transaccion` (`TransactionTemplate` sobre `TenantAwareTransactionManager`), traducción `23P01`/`23503` a errores de dominio; `JdbcReservaRepositoryTest` (RV6) y prueba de extremo a extremo de `ReservarPista` contra la BD real. | RV6, D-009 | T4, T7 |
| T10 | **Mutaciones por tier**, una a una, aisladas y revertidas con `mvn clean verify` (confirmar que el build cae tras `clean`): tier dominio (RV1, RV3a, RV9); tier BD (quitar constraint → RV2 y RV4; `'[]'`; sin `WHERE`; FK sin `tenant_id`; `GRANT DELETE`; `NO FORCE`); adaptador sin traducción (RV6); escenario RV4-b. Evidencia (qué se mutó, qué cayó) en el PR. | §5.2, DoD | T4, T5, T6, T7, T9 |
| T11 | `CLAUDE.md`: sección Reservas (invariante en dos capas, constraint parcial, mutaciones por tier, regla del aserto de bloqueo de RV4, identificadores ASCII). ‖ T10 | D-007, D-009, punto 1 | T9 |
| T12 | Bucle local `mvn -B clean verify` en verde y run del PR en el runner self-hosted; PR `feat/reservas` con origen D-007..D-009 y la evidencia de T3, T8 y T10; **`/revision-adversarial` antes del merge**; sin merge hasta el visto de Pe. | DoD | T10, T11 |

Camino crítico: T1 → T3 → T4 → T6/T9 → T10 → T12.

## 9. GATE DE CLARIFICACIÓN — RESUELTO por Pe (2026-10-30)
Pe acepta los 17 puntos con sus defaults, con dos matices:
- **Punto 1:** identificadores ASCII en gallego (`SlotNonDisponible`, `Franxa`, `PistaNonExiste`, `ReservaXaCancelada`); la `ñ` solo en documentación y en strings de usuario.
- **Punto 11:** aceptado con la separación de mutaciones **por tier** y la regla del aserto de bloqueo de RV4 (ver §5.2).
- **Punto 17:** D-007, D-008 y D-009 pasan a ACEPTADA.

Puntos y propuestas originales (todas aceptadas):
| # | Ambigüedad | Propuesta por defecto |
|---|---|---|
| 1 | **Nombres en el código.** Tu ejemplo `SlotNoDispoñible` lleva `ñ`; es Java legal, pero un fichero con `ñ` en macOS (normalización NFD de APFS) puede dar problemas con git y CI. ¿Gallego con ñ, ASCII, o castellano? | Identificadores **ASCII** en gallego: `SlotNonDisponible`, `Franxa`, `Reserva`, `PistaNonExiste`, `ReservaXaCancelada`. El lenguaje ubicuo con ñ vive en la documentación. |
| 2 | **`tenant_id` dentro de la exclusion constraint** (D-008). | Sí, `tenant_id WITH =` + `pista_id WITH =` + rango. |
| 3 | **Semántica del intervalo.** | Semiabierto `[inicio, fin)`; `CHECK (fin > inicio)`; sin granularidad, duración mínima ni máxima (spec de normas). |
| 4 | **Reserva sin titular** hasta la spec de auth. | Sí. |
| 5 | **Sin REST ni UI** en este módulo; se ejerce por casos de uso y tests. | Sí. |
| 6 | **Precisión:** normalizar la `Franxa` a microsegundos. | Sí. |
| 7 | **Reservas en el pasado:** sin regla (necesita reloj). | Fuera de alcance. |
| 8 | **`btree_gist`:** `CREATE EXTENSION IF NOT EXISTS` dentro de `V3` (una sola migración), riesgo R-1 documentado. | Una migración `V3`. |
| 9 | **Pista mínima:** tabla `pista(id, tenant_id, nome)`, sin aggregate `Pista` en el dominio, sin semilla, la app solo lee; `reserva` sin `DELETE`. | Sí. |
| 10 | **Transacción:** puerto `Transaccion` en `application` + `TransactionTemplate` en infraestructura (mantiene `application` sin Spring). | Sí. |
| 11 | **RV2 y RV4 comparten mutación** (quitar la constraint tumba ambos tests). ¿Lo aceptas como una regla con dos facetas? | Sí. |
| 12 | **Recursos jerárquicos** (pista dividida) fuera de alcance. | Fuera, anotado como R-3. |
| 13 | **Cancelar una reserva ya cancelada:** error de dominio (`ReservaXaCancelada`), no idempotente; `cancelada` es terminal (no se reactiva). | Error, terminal. |
| 14 | **Orden de comprobación:** check de dominio previo y `INSERT` después, con la BD como última palabra; sin reintentos automáticos. | Sí. |
| 15 | **Ids generados por la aplicación** (no por defecto de BD). | Sí. |
| 16 | **`estado` como `text` + `CHECK`** (no enum de Postgres). | Sí. |
| 17 | **Decisiones nuevas:** D-007 (invariante en dos capas, intervalo `[)`, constraint parcial), D-008 (`tenant_id` en la constraint, FK compuesta, mínimo privilegio), D-009 (traducción de errores en el adaptador, puerto `Transaccion`). Constan como **PROPUESTA** y pasan a ACEPTADA con tu visto. | Aceptar las tres. |
