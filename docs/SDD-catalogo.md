# SDD — Catálogo: instalaciones, pistas, horarios y normas de uso

Estado: **APROBADA por delegación (Pe: «decide tú», 2026-11-10); pendiente de revisión de Pe en este PR** · §8 Plan completo · sin código ni migraciones en este PR.
Normativo: `CLAUDE.md`, `docs/SDD-multi-tenant.md`, `docs/SDD-reservas.md`, `docs/decisiones.md` (D-003..D-025). Origen: `docs/REQUISITOS.md` (C1, M1, M2, R1), bases v3 puntos 1.1 y 1.2, ítem conocido «Catálogo de pistas» de `docs/decisiones.md`.

## 0. Meta
- **Proyecto:** PistaAberta (I Certame GOBTEC) · repo `jsampedroc/pistaberta`
- **Fecha:** 2026-11-10
- **Tipo:** módulo (segundo dominio sobre el fundamento multi-tenant y las reservas)
- **Target:** `origin/main` vía PRs independientes (PR-1..PR-4, §8), nunca apilados
- **Fichero:** `docs/SDD-catalogo.md`
- **Plazo de la candidatura:** 2026-11-18 23:59 (bases v3, por correo a `certame@agasol.gal`)
- **Aterrizado contra `origin/main@8091b8b`:**
  - Migraciones `V1..V4`. `V3__pista_reserva.sql` crea `pista` como stub (`id` PK global, `nome`, `UNIQUE (tenant_id, id)` destino de `reserva_pista_fk`) y `reserva` (PK `(tenant_id, id)`, `reserva_sen_solape`, trigger `reserva_non_reabrir`). Las nuevas son `V5`, `V6`, `V7`.
  - Casos de uso `ReservarPista` y `CancelarReserva` en `application`, dentro del puerto `Transaccion`; `ReservarPistaConAcceso` emite el token (D-011). `JdbcReservaRepository` traduce `23P01` y `23503` (D-009).
  - Acceso a datos por `TenantScopedJdbc`; `TenantFilter` y resolver de tenant por la variable de ruta ya parseada en las rutas con token (D-014).
  - `TenantCatalogGuardTest` revisa todas las tablas de `public`.
  - La app no tiene `INSERT` sobre `pista` hoy; las pistas de test las crea el owner.

## 1. OBJETIVO
Dar a cada concello un **catálogo propio** de instalaciones y pistas (C1, M1) con **horarios y normas de uso** (M2), configurable sin tocar código (R1). Es la base de «consultar instalaciones» para la ciudadanía y de que la reserva respete el horario real, algo que hoy no hace: se puede reservar a las 3 de la madrugada. Se construye sobre el núcleo multi-tenant y de reservas sin debilitar sus invariantes (D-003..D-010), y rehace `pista` con PK `(tenant_id, id)` como pide el ítem conocido de `docs/decisiones.md`.

## 2. QUÉ
### 2.1 In-scope (criterios verificables)
| ID | Criterio |
|---|---|
| CT1 | **Instalación y pista:** una instalación contiene N pistas con deporte y estado. `instalacion.estado` ∈ `activa`/`pechada`; `pista.estado` ∈ `activa`/`mantemento`/`pechada`. Todo tenant-scoped con RLS + filtro aplicativo. |
| CT2 | **PK `(tenant_id, id)`** en `pista`: un id de otro concello no colisiona ni revela existencia (test que muerde, como D-010). `reserva_pista_fk` conserva su nombre literal. |
| CT3 | **Alta y mantenimiento** por el concello (crear, editar, cambiar estado). Nada se borra: se cierra (`pechada`). |
| CT4 | **Horario semanal** por instalación: franjas por día ISO (1..7) en hora local `Europe/Madrid`, sin solapes entre franjas del mismo día (exclusion de BD). |
| CT5 | **Excepciones:** festivos y cierres con motivo, como rango `timestamptz`, que prevalecen sobre el horario semanal. |
| CT6 | **Normas de uso por instalación:** duración de la franja reservable, antelación mínima y máxima, límite de reservas por persona (nulo = sin límite, D-024). Sin normas, la instalación no es reservable (D-022). |
| CT7 | **Reserva dentro de horario, en dos capas:** dominio (`HorarioReservable`, error propio) y BD (trigger `reserva_dentro_horario`: franja, excepción, estado de pista e instalación). Ninguna sustituye a la otra (D-023). |
| CT8 | **Consulta pública de solo lectura:** listar instalaciones, ver detalle (pistas, horario, excepciones, normas) y franjas libres de una pista para un día. Tenant por slug de ruta (D-020). |
| CT9 | **Configuración por concello:** nombre público, idioma por defecto y zona horaria (solo `Europe/Madrid`, con `CHECK`). |
| CT10 | **Límite por persona** por identificador de cliente derivado de la IP, guardado solo como HMAC (D-024). Regla de cortesía, no de seguridad. |

### 2.2 Out-of-scope (explícito)
- **Autenticación y roles** (spec de auth, D-017). Las escrituras del concello van tras un puerto `Autorizacion`: por cabecera en dev/test y **cerradas en prod** hasta esa spec.
- Fotos, mapa, geolocalización (PostGIS), accesibilidad física de la instalación.
- Tarifas, precios, pagos, penalizaciones por no presentarse, reparto de temporadas a clubs.
- Modificar una reserva e historial (spec aparte). Cancelar automáticamente reservas afectadas por un cambio de horario (D-025).
- Avisos y notificaciones.
- Zona horaria distinta de `Europe/Madrid`.
- Actividades y cursos, lista de espera, pistas divididas o con aforo compartido.
- Front y UI (spec del front).
- Rate limiting y anti-fuerza bruta.

## 3. CÓMO
- **Stack:** Java 21 · Spring Boot 3.3 · PostgreSQL 16 + PostGIS · Flyway · Testcontainers. Multi-tenancy: **sí** (heredada). Auth: **ninguna** aquí (puerto `Autorizacion`). Sin Kafka/Redis/MinIO.
- **Hexagonal** (`domain` ← `application` ← `infrastructure`; sin Spring ni JPA en las dos primeras):

| Capa | Piezas nuevas |
|---|---|
| `domain` | `Instalacion`, `Pista`, `EstadoInstalacion`, `EstadoPista`, `NormasUso`, `HorarioSemanal`, `FranxaHoraria`, `ExcepcionHorario`, `HorarioReservable` (puro, `Europe/Madrid`), `ForaDeHorario`, `PistaNonActiva`, `AntelacionInvalida`, `LimiteReservas` |
| `application` | Casos de uso de alta y edición de instalación y pista, de horario, excepciones, normas y configuración; `ConsultarCatalogo`, `ConsultarFranxasLibres`. Puertos `Autorizacion`, `RepositorioCatalogo`, `RepositorioCliente`. `ReservarPista` pasa a comprobar `HorarioReservable` y el límite |
| `infrastructure` | Repositorios por `TenantScopedJdbc`, controladores REST, `HmacCliente` (HMAC-SHA256 de la IP), traducción de errores del trigger en `JdbcReservaRepository`, migraciones |

- **Zona horaria:** toda comparación de horario se hace en `Europe/Madrid` (DST incluido). Las franjas se guardan como minutos del día local (`abre_min`, `pecha_min`); las excepciones y reservas, como `timestamptz`.

## 4. MODELO DE DATOS
Migraciones nuevas (`V1..V4` no se tocan): **`V5`** esquema del catálogo, **`V6`** trigger `reserva_dentro_horario`, **`V7`** `reserva_cliente`. Todas las tablas nuevas: `tenant_id uuid not null`, `ENABLE` + `FORCE ROW LEVEL SECURITY` con policy `app_tenant_id()`, índice que empieza por `tenant_id`, unicidades y FKs con `tenant_id`, `GRANT` explícito a `pistaberta_app`.

| Tabla | Contenido | `GRANT` a la app |
|---|---|---|
| `instalacion` | PK `(tenant_id, id)`, `nome`, `estado` (`CHECK`) | `SELECT`, `INSERT`, `UPDATE (nome, estado)` |
| `pista` (**rehecha en V5**) | PK pasa a `(tenant_id, id)`; añade `instalacion_id` (FK compuesta a `instalacion`), `deporte`, `estado` (`CHECK`). `pista_tenant_id_uq` y `reserva_pista_fk` se conservan por nombre | `SELECT`, `INSERT`, `UPDATE (nome, deporte, estado)` |
| `horario_franxa` | PK `(tenant_id, id)`, `instalacion_id`, `dia_semana` 1..7, `abre_min`/`pecha_min` 0..1440, `CHECK (pecha_min > abre_min)`, **exclusion** `(tenant_id, instalacion_id, dia_semana, int4range(abre_min, pecha_min))` sin solapes | `SELECT`, `INSERT`, `DELETE` |
| `excepcion_horario` | PK `(tenant_id, id)`, `instalacion_id`, `inicio`/`fin` `timestamptz`, `motivo`, `CHECK (fin > inicio)` | `SELECT`, `INSERT`, `DELETE` |
| `normas_uso` | PK `(tenant_id, instalacion_id)`; `duracion_min > 0`, `antelacion_min_min >= 0`, `antelacion_max_dias > 0`, `max_reservas_persoa` nullable `> 0` | `SELECT`, `INSERT`, `UPDATE` |
| `config_concello` | PK `tenant_id`; `nome_publico`, `idioma`, `zona_horaria` con `CHECK = 'Europe/Madrid'` | `SELECT`, `INSERT`, `UPDATE` |
| `reserva_cliente` (V7) | PK `(tenant_id, reserva_id)` → FK compuesta a `reserva`; `cliente_hash bytea` (32 bytes) y `creado`; índice `(tenant_id, cliente_hash)` | `SELECT`, `INSERT`, `DELETE` (borrado por retención, D-024) |

**`V5` con datos previos:** cada `pista` existente recibe una instalación homónima del mismo tenant antes de imponer `instalacion_id NOT NULL`.

**Trigger `reserva_dentro_horario` (V6),** `BEFORE INSERT ON reserva`, solo si `NEW.estado = 'confirmada'`, `SECURITY INVOKER` (respeta RLS con el rol de la app). Rechaza con `ERRCODE = 'check_violation'` y `CONSTRAINT` literal:
- `reserva_pista_non_activa`: la pista o su instalación no están `activa`.
- `reserva_fora_horario`: la reserva no cabe entera en **una** franja del día local (sin cruzar medianoche; `fin` = 24:00 permitido).
- `reserva_en_excepcion`: `tstzrange(inicio, fin)` solapa una excepción de la instalación.

Solo en dominio, con límite documentado: antelación mínima y máxima (dependen del reloj), duración exacta y rejilla, y límite por persona.

## 5. CONTRATOS/API
Lecturas públicas: el tenant sale de la variable de ruta ya parseada (D-014 extendido, D-020), también en prod, con 404 uniforme para slug desconocido. Escrituras: por cabecera, solo dev/test, tras `Autorizacion`; cerradas en prod.

| Método | Ruta | Propósito | Auth |
|---|---|---|---|
| GET | `/api/t/{slug}/instalacions` | Listar instalaciones | pública |
| GET | `/api/t/{slug}/instalacions/{id}` | Detalle: pistas, horario, excepciones, normas | pública |
| GET | `/api/t/{slug}/pistas/{id}/franxas?data=` | Franjas libres de un día (rejilla menos reservas y excepciones) | pública |
| GET | `/api/t/{slug}/config` | Configuración del concello | pública |
| POST / PATCH | `/api/instalacions[/{id}]` | Alta y edición | `Autorizacion` |
| POST / PATCH | `/api/instalacions/{id}/pistas`, `/api/pistas/{id}` | Alta y edición de pista | `Autorizacion` |
| PUT | `/api/instalacions/{id}/horario` | Reemplaza el horario semanal | `Autorizacion` |
| POST / DELETE | `/api/instalacions/{id}/excepcions[/{eid}]` | Cierres y festivos | `Autorizacion` |
| PUT | `/api/instalacions/{id}/normas` | Normas de uso | `Autorizacion` |
| PUT | `/api/config` | Configuración | `Autorizacion` |

Las escrituras que afectan a reservas confirmadas futuras (horario, excepción, estado de pista o de instalación) devuelven `reservas_afectadas` (lista de ids) y no cancelan nada (D-025).
`POST` de reservar (existente) añade errores de dominio traducidos por `ProblemasHttp`: `fora_de_horario`, `pista_non_activa`, `antelacion_invalida`, `limite_reservas`.

## 6. CUÁNDO PARAR (DoD)
- Criterios CT1..CT10 de §2.1 verificados, cada uno con un test que muerde.
- `mvn -B clean verify` en verde (local y CI); `V5..V7` aplican limpios sobre `V1..V4` y sobre una BD con pistas previas (test de migración).
- `TenantCatalogGuardTest` cubre las tablas nuevas **sin tocar su allowlist**.
- Tests críticos: PK de `pista` por tenant (CT2); el trigger rechaza fuera de franja, en excepción y en pista o instalación no activa, y deja pasar el borde `[inicio, fin)` y los días de cambio de hora (DST); el dominio y la BD fallan **por separado**; límite por persona con contador por HMAC y retención.
- **Mutaciones aisladas por tier** (`mvn clean verify`, una a una; cada una tumba solo los tests de su regla):
  - dominio: `HorarioReservable` sin efecto; excepción ignorada; borde con `<=`; conversión de zona sin DST; límite por persona sin contar
  - BD: quitar el trigger; trigger sin la rama de excepciones; trigger sin la de estado; PK de `pista` otra vez `(id)`; `GRANT UPDATE` de tabla entera; `NO FORCE`
  - adaptador: error del trigger sin traducir
- Los tests de mutación de BD dejan los datos de modo que la barrera de dominio no los frene antes, y viceversa; si no, el test sería mudo (mismo criterio que RV4).
- Sin merge a `main` sin validación de Pe.
- **NO pulir:** fotos, mapa, tarifas, textos legales, UI, avisos.

## 7. RESTRICCIONES / no-gos
- No tocar `V1..V4`. Nombres de constraint literales en la migración y en el adaptador (`CONSTRAINT_PISTA` y los tres nuevos); una divergencia la cazan las mutaciones de adaptador.
- Identificadores en gallego y ASCII; sin async ni hilos virtuales con acceso a datos; sin `ddl-auto`.
- Datos personales: de la IP solo se guarda su HMAC (D-024). Prohibido guardar la IP en claro, en tabla o en logs de aplicación.
- Semilla de instalaciones de A Estrada y Lalín solo en `db/seed` (dev/test); nunca en `db/migration`.
- Cada PR nombra su origen; las decisiones ACEPTADAS van a `docs/decisiones.md` en el mismo PR.

## 8. PLAN DE EJECUCIÓN
Tareas con ID. `‖` = paralelizable (misma sesión no escribe en paralelo: el repo admite una sesión con escritura; `‖` solo indica independencia de orden). Cada PR sale de `origin/main` limpio tras el merge del anterior, nunca apilado.

| ID | Tarea | Origen | Depende de |
|---|---|---|---|
| T1 | `V5`: `instalacion`, `pista` rehecha (PK `(tenant_id, id)`, backfill), `horario_franxa`, `excepcion_horario`, `normas_uso`, `config_concello`, RLS y grants. Adaptar tests existentes que crean `pista` | CT1, CT2, CT4, CT5, CT6, CT9, D-021 | — |
| T2 ‖ | Dominio del catálogo: `Instalacion`, `Pista`, estados, `NormasUso`, `HorarioSemanal`, `FranxaHoraria`, `ExcepcionHorario` | CT1, CT4, CT5, CT6 | — |
| T3 ‖ | Dominio `HorarioReservable` y errores (`ForaDeHorario`, `PistaNonActiva`, `AntelacionInvalida`), rejilla de franjas | CT7, D-022, D-023 | T2 |
| T4 | `V6`: trigger `reserva_dentro_horario` y su traducción en `JdbcReservaRepository` | CT7, D-023 | T1 |
| T5 | Mutaciones y tests de falsabilidad de CT2 y CT7 (dominio, BD, adaptador) | DoD §6 | T3, T4 |
| T6 | Casos de uso de escritura (instalación, pista, horario, excepciones, normas, config), `RepositorioCatalogo` JDBC, `reservas_afectadas` | CT3..CT6, CT9, D-025 | T1, T2 |
| T7 | Controladores REST de escritura tras `Autorizacion` (dev/test; cerrados en prod) | CT3..CT6, CT9, D-012 | T6 |
| T8 | `ReservarPista` integra `HorarioReservable` y normas; errores en `ProblemasHttp` | CT6, CT7, D-022 | T3, T4, T6 |
| T9 | `V7` `reserva_cliente`, `HmacCliente`, límite por persona y retención de 30 días | CT10, D-024 | T8 |
| T10 | Consultas públicas (`ConsultarCatalogo`, `ConsultarFranxasLibres`), rutas por slug y resolver de tenant (D-020) | CT8, D-020 | T6 |
| T11 | Semilla dev/test, actualizar `docs/REQUISITOS.md` y la tabla «Estado» del `README.md` | D-021, REQUISITOS | T7, T10 |

**PRs:** PR-1 = T1+T2+T3+T4+T5 (esquema, dominio y barrera de BD, con sus mutaciones) · PR-2 = T6+T7 (administración del catálogo) · PR-3 = T8+T9 (reserva integrada y límite por persona) · PR-4 = T10+T11 (consultas públicas y cierre). Si el plazo aprieta, D-019 manda: se recorta antes PR-3 en lo relativo a T9 (límite por persona) que PR-1, PR-2 y T10.

## 9. Riesgos y límites conocidos
- **IP como identificador (D-024):** es dato personal; se mitiga guardando solo el HMAC con secreto del servidor y borrando a los 30 días. Varios usuarios tras un NAT comparten IP, por eso el límite es nulo por defecto. Se salta cambiando de red: regla de cortesía, no de seguridad.
- **Cierres posteriores a la reserva:** una excepción creada después no cancela reservas ya confirmadas (D-025); el trigger solo actúa al insertar.
- **Antelación y duración solo en dominio:** una reserva insertada por SQL directo con el rol de la app podría saltárselas; es un límite conocido (el rol no tiene `UPDATE` sobre la franja, pero sí `INSERT`).
- **Tenant en lecturas públicas (D-020):** depende del resolver por variable de ruta; el bypass `/api;x=1/` ya ocurrió una vez y hay test.
- **Escrituras cerradas en prod:** hasta la spec de auth la candidatura se demuestra en el perfil `dev`.
- **`TenantCatalogGuardTest`:** si una tabla nueva queda sin RLS forzada, el build cae; no hay que tocar su allowlist.

## 10. Clarificación resuelta (2026-11-10, por delegación de Pe: «decide tú»)
| # | Resolución | Decisión |
|---|---|---|
| 1 | IP guardada solo como HMAC-SHA256 con secreto del servidor, borrado a 30 días; IP de `X-Forwarded-For` solo con proxy de confianza configurado | D-024 |
| 2 | Límite por persona nulo por defecto | D-024 |
| 3 | La reserva cabe en una sola franja, sin cruzar medianoche | D-022 |
| 4 | Duración exacta y rejilla alineada con la apertura de la franja | D-022 |
| 5 | Sin normas, la instalación no es reservable | D-022 |
| 6 | Estados de instalación y pista; instalación `pechada` cierra todas sus pistas | D-021, D-023 |
| 7 | Reservas existentes no se cancelan al cambiar horario; se devuelven en `reservas_afectadas` | D-025 |
| 8 | Escrituras cerradas en prod hasta la spec de auth | D-012 |
| 9 | Lecturas públicas con tenant por slug de ruta | D-020 |
| 10 | `V5` crea una instalación homónima por pista existente | D-021 |
| 11 | Semilla solo dev/test | D-021 |
| 12 | Zona horaria `Europe/Madrid` fija con `CHECK` | D-022 |
