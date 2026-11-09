# SDD — Extras GOBTEC: calendario personal, acceso por QR y analítica con recomendación de horarios

Estado: **APROBADA por el/la mantenedor(a) (clarificación resuelta el 2026-11-09)** · §8 Plan completo · sin código ni migraciones en este PR; la implementación empieza en PR-A tras el visto del/de la mantenedor(a) a este PR.
Normativo: `CLAUDE.md`, `docs/SDD-multi-tenant.md`, `docs/SDD-reservas.md`, `docs/decisiones.md` (D-003..D-016 aceptadas).

## 0. Meta
- **Proyecto:** PistaAberta (I Certame GOBTEC) · repo `jsampedroc/pistaberta`
- **Fecha:** 2026-11-09
- **Tipo:** módulo (tres extras sobre el núcleo de reservas)
- **Target:** `origin/main` vía tres PRs independientes (PR-A, PR-B, PR-C), nunca apilados
- **Fichero:** `docs/SDD-extras.md`
- **Plazo de la candidatura:** 2026-11-18, 23:59 (según la noticia de la convocatoria; las bases v3 pueden haberlo modificado: comprobar en la web de AGASOL)
- **Aterrizado contra `origin/main@9482fd7`:**
  - Migraciones `V1`, `V2`, `V3__pista_reserva.sql` (`pista`, `reserva`, `reserva_sen_solape`, trigger `reserva_non_reabrir`). La migración nueva es `V4`.
  - Casos de uso `ReservarPista` y `CancelarReserva` en `application`, dentro del puerto `Transaccion`. No hay API REST, ni auth, ni resolver de tenant en prod (D-005).
  - `reserva` no tiene titular: no hay solicitante hasta la spec de auth (`SDD-reservas.md` §2.2).

## 1. OBJETIVO
Sumar los tres extras que las bases puntúan: **calendario personal** (feed `.ics`), **acceso por QR** (token firmado por reserva, con validación) y **analítica de uso con recomendación de horarios**. Se construyen sobre el núcleo de reservas ya aceptado (D-007..D-010) sin debilitar sus invariantes (no-solapamiento en dos capas, multi-tenant con RLS). El objetivo es tener código real y verificable, no solo demo, antes del cierre de la candidatura, con un alcance mínimo y falsable por extra.

Lenguaje ubicuo (gl/es):

| Término | Significado |
|---|---|
| **Token de acceso** | Secreto opaco de 256 bits emitido al reservar; da acceso al `.ics`, al QR y a la validación de **esa** reserva. Es un ticket al portador. |
| **Acceso** (`ReservaAcceso`) | Registro tenant-scoped con el hash SHA-256 del token. Uno por reserva. |
| **Validación** | Consulta del estado de un token: `valida`, `fora_de_hora` o `cancelada`. |
| **Ocupación** | Proporción de días de la ventana en que una hora local está ocupada por una reserva confirmada. |
| **Recomendación** | Las 3 franjas libres con menor ocupación histórica para un día y una pista. |

## 2. QUÉ
### 2.1 In-scope (criterios verificables)
| ID | Criterio |
|---|---|
| EX0 | **Acceso por token:** al reservar se emite un token opaco de 256 bits, devuelto **una sola vez**. En BD solo el SHA-256 en `reserva_acceso`; `reserva` sigue inmutable (D-010). Token, slug o tenant desconocidos → el mismo 404 (mismo status, cuerpo y cabeceras). Emisión y reserva en la **misma transacción**. |
| EX1 | **Calendario:** `GET calendario.ics` con token devuelve un `.ics` (RFC 5545) con un `VEVENT`: `UID` estable (id de la reserva), `DTSTART`/`DTEND` en UTC, nombre de la pista. Cancelada → `STATUS:CANCELLED` y `SEQUENCE` 1 (confirmada = 0). |
| EX2 | **QR:** `GET qr.png` con token devuelve un PNG que codifica la URL de validación. Decodificar el QR da la URL exacta. |
| EX3 | **Validación:** `GET acceso/{token}` responde `valida` / `fora_de_hora` / `cancelada`. Válida desde `inicio − 15 min` (incluido) hasta `fin` (excluido), con puerto `Clock` inyectable. |
| EX4 | **Analítica:** ocupación por franja horaria (60 min, hora local `Europe/Madrid`) y pista en los últimos `dias` días completos (sin hoy), solo reservas `confirmada`, por tenant. Lectura pura. |
| EX5 | **Recomendación:** para un día y una pista, las 3 franjas libres con menor ocupación histórica, cada una con su porcentaje. Solo horas con al menos una reserva en la ventana. Determinista (desempate por hora ascendente). Sin ML. |
| EX6 | **Aislamiento y privilegios:** `reserva_acceso` cumple C6 (`tenant_id`, RLS + FORCE, `GRANT` explícito solo `SELECT, INSERT`). La analítica no ve datos de otros tenants. Un token de un tenant no resuelve en otro. |
| EX7 | `domain` y `application` siguen sin Spring ni JPA (enforcer verde). QR (ZXing) y `.ics` viven solo en `infrastructure`. `V1`..`V3` no se tocan. |

### 2.2 Out-of-scope (explícito)
- **Cuentas, login, roles y auth.** El token es un ticket al portador. La spec de auth sigue pendiente.
- **App/UI de escaneo** para el personal del concello y registro de entradas. Solo existe el endpoint de validación.
- **Marcar la entrada como usada** (anti-reutilización del QR). Validar no cambia el estado de la reserva.
- **Agenda con varias reservas por persona** y suscripción `webcal` multi-evento (D-013). Hoja de ruta: se añadiría con una migración nueva.
- **Notificaciones** (email, push) y envío del `.ics` por correo.
- **ML, predicción, estacionalidad**, festivos y meteorología.
- **Reglas de calendario del concello** (horario de apertura, antelación, duración). El horario operativo se infiere del histórico (D-016).
- **Zona horaria por concello:** `Europe/Madrid` fija.
- **Revocar o regenerar tokens**, y expiración por tiempo más allá de la ventana de EX3.
- **Rate limiting y anti-fuerza bruta.** Con 256 bits la enumeración es inviable; queda como ítem conocido.
- **Front React.** El demo ya muestra el QR y el panel; aquí solo se entrega backend real.
- **Cancelar ya identificado:** en dev/test cualquiera puede cancelar cualquier reserva por cabecera; quién puede cancelar es de la spec de auth.

## 3. CÓMO
- **Stack:** Java 21 · Spring Boot 3.3 · PostgreSQL 16 + PostGIS · Flyway · Testcontainers. Multi-tenancy: **sí** (heredada). Auth: ninguna (token al portador). Sin Kafka/Redis/MinIO.
- **Hexagonal** (`domain` ← `application` ← `infrastructure`; sin Spring ni JPA en las dos primeras):

| Capa | Piezas nuevas |
|---|---|
| `domain` | `TokenAcceso` (valor), `EstadoAcceso`, `OcupacionFranxa`, `RecomendadorFranxas` (puro, determinista) |
| `application` | Puertos `GeneradorToken`, `RepositorioAcceso`, `Clock` (`java.time.Clock`). Casos de uso `EmitirAcceso` (en la misma transacción que `ReservarPista`), `ValidarAcceso`, `ObterCalendario`, `ObterQr`, `ConsultarOcupacion`, `RecomendarFranxas` |
| `infrastructure` | Controladores REST, `JdbcRepositorioAcceso` (por `TenantScopedJdbc`), `IcsWriter` (RFC 5545 a mano, ~40 líneas, sin librería), `QrZxing` (ZXing, Apache-2.0), consultas de analítica |

- **Token:** `SecureRandom` de 32 bytes en Base64URL; en BD solo su `SHA-256`; comparación en tiempo constante.
- **Analítica:** `generate_series` por hora en `Europe/Madrid`, solo `confirmada`; `EXPLAIN` medido con volumen (mismo criterio que T8 de `SDD-reservas.md`).
- **Tenant en las rutas con token (D-014):** el slug va en la ruta (`/api/t/{slug}/...`) y un `TenantResolver` adicional lo lee de la **variable de ruta ya parseada**, nunca por prefijo de URL (el bypass `/api;x=1/...` ya ocurrió: ver `CLAUDE.md`). Las rutas por cabecera (`POST` reservar/cancelar y analítica) siguen **solo dev/test**: abrirlas en prod sería permitir escrituras anónimas, y eso es la spec de auth. Consecuencia: en prod, hasta que exista auth, los extras solo se ejercen sobre reservas ya creadas; la candidatura se demuestra en el perfil `dev`.
- **Identificadores** en gallego y ASCII (`ReservaAcceso`, `AccesoNonValido`, `OcupacionFranxa`…), como manda `CLAUDE.md`.

## 4. MODELO DE DATOS
**Migración objetivo:** `V4__reserva_acceso.sql`. No toca `V1`..`V3`.

```sql
CREATE TABLE reserva_acceso (
    tenant_id   uuid        NOT NULL REFERENCES tenant (id),
    reserva_id  uuid        NOT NULL,
    token_hash  bytea       NOT NULL,           -- SHA-256 del token; el token en claro no se guarda
    creado      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT reserva_acceso_pkey       PRIMARY KEY (tenant_id, reserva_id),
    CONSTRAINT reserva_acceso_reserva_fk FOREIGN KEY (tenant_id, reserva_id)
                                         REFERENCES reserva (tenant_id, id),
    CONSTRAINT reserva_acceso_hash_uq    UNIQUE (tenant_id, token_hash),
    CONSTRAINT reserva_acceso_hash_ck    CHECK (octet_length(token_hash) = 32)
);
ALTER TABLE reserva_acceso ENABLE ROW LEVEL SECURITY;
ALTER TABLE reserva_acceso FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON reserva_acceso
    USING (tenant_id = app_tenant_id()) WITH CHECK (tenant_id = app_tenant_id());
GRANT SELECT, INSERT ON reserva_acceso TO pistaberta_app;   -- sin UPDATE ni DELETE
```

- **Tabla aparte, no columna en `reserva`:** `reserva` sigue inmutable para la app (D-010: solo `UPDATE (estado)`).
- **Un token por reserva:** lo impone la PK; la FK es compuesta con `tenant_id` (C6).
- **Índice de búsqueda por token:** el `UNIQUE (tenant_id, token_hash)` (empieza por `tenant_id`); no hace falta otro. La unicidad es por tenant porque el tenant llega siempre de la ruta.
- **Tokens inmutables:** sin `UPDATE`/`DELETE`; revocar o regenerar está fuera de alcance. Si se pierde el token, no se reemite (límite conocido, D-015).
- **`SEQUENCE` del `.ics`:** derivado del estado de la reserva (`confirmada` 0, `cancelada` 1); sin columna nueva.
- **`TenantCatalogGuardTest`:** `reserva_acceso` cumple C6 y pasa sin tocar la allowlist.
- **Analítica:** sin tablas nuevas; lee `reserva` y `pista` con los `SELECT` ya concedidos. Sirve el índice `reserva_tenant_pista_inicio_idx`; no se añade otro hasta medir con `EXPLAIN`.

## 5. CONTRATOS/API
Errores de los casos de uso y de MVC en `application/problem+json` (RFC 7807), sin trazas ni SQL. Los del `TenantFilter` (sin tenant, tenant desconocido, prod sin resolver) salen sin cuerpo: ocurren antes de MVC (`sendError`). Franxa fuera de `[1900-01-01, 9999-12-31T23:59:59Z]` → 400 `FranxaInvalida` (guarda técnica del adaptador: el driver convierte las fechas muy anteriores en `-infinity` y Postgres rechaza las posteriores al año 294276).

### Rutas con cabecera `X-Tenant-Id` (solo perfiles `dev`/`test`)
| Método | Ruta | Propósito | Auth |
|---|---|---|---|
| `POST` | `/api/pistas/{pistaId}/reservas` | Reservar. Body `{inicio, fin}` (ISO-8601 con zona). **201** `{reservaId, inicio, fin, estado, token, urls}` (el `token` solo aquí). **409** `SlotNonDisponible` · **404** `PistaNonExiste` · **400** franxa inválida | ninguna |
| `POST` | `/api/reservas/{id}/cancelar` | Cancelar (libera la franxa). **204** · **404** no existe · **409** `ReservaXaCancelada` | ninguna |
| `GET` | `/api/pistas/{pistaId}/ocupacion?dias=30` | Ocupación por franja horaria (EX4). `dias` 1..90, por defecto 30; fuera de rango → 400 | ninguna |
| `GET` | `/api/pistas/{pistaId}/recomendacion?data=YYYY-MM-DD&dias=30` | 3 franjas libres de menor ocupación, con su porcentaje (EX5). `data` anterior a hoy → 400 | ninguna |

### Rutas con token (tenant por slug en la ruta; sin cabecera)
| Método | Ruta | Propósito | Auth |
|---|---|---|---|
| `GET` | `/api/t/{slug}/acceso/{token}` | Validación (EX3). **200** `{estado, pista, inicio, fin}` con `estado` = `valida` · `fora_de_hora` · `cancelada` | token |
| `GET` | `/api/t/{slug}/acceso/{token}/calendario.ics` | `text/calendar` con el `VEVENT` (EX1) | token |
| `GET` | `/api/t/{slug}/acceso/{token}/qr.png` | `image/png` con el QR de la URL de validación (EX2) | token |

### Reglas transversales
- **404 uniforme:** token desconocido, slug desconocido o token de otro tenant devuelven el mismo 404 (mismo cuerpo y misma forma). «Desconocida» se expresa como 404, no como un estado 200.
- **Reserva cancelada** sigue resolviendo: el `.ics` sale con `STATUS:CANCELLED`; la validación responde `cancelada` con 200. **Reserva pasada:** validación `fora_de_hora`; `.ics` y QR se siguen sirviendo.
- **Cabeceras en rutas de token:** `Cache-Control: no-store` y `Referrer-Policy: no-referrer`.
- **El token no se escribe en logs** (va en la URL): el access log lo enmascara.
- **URL pública del QR:** propiedad `pistaberta.public-base-url`, sin valor por defecto fuera de `dev`.

## 6. CUÁNDO PARAR (DoD)
### Test que muerde y mutación aislada por regla
Cada mutación se hace **una a una con `mvn clean verify`** (nunca sin `clean`) y se comprueba que el build cae tras `clean`; si no cae, la mutación no prueba nada.

| Regla | Test que muerde (oráculo) | Mutación aislada → cae |
|---|---|---|
| EX0 token no en claro | Tras reservar, `token_hash` ≠ token y es su SHA-256 | Guardar el token en claro → `CHECK octet_length = 32` y el test |
| EX0 404 uniforme | Token desconocido, slug desconocido y token de otro tenant: mismo status, cuerpo y cabeceras | Mensaje distinto para slug inexistente → test de uniformidad |
| EX0 atomicidad | Si falla el insert del token, la reserva hace rollback | Emitir el token en otra transacción → test de atomicidad |
| EX1 `.ics` | Parseado con **ical4j en scope test** (oráculo independiente): `UID`, `DTSTART` en UTC, `STATUS`, `SEQUENCE` | No subir `SEQUENCE` al cancelar → test |
| EX2 QR | Se decodifica con el lector de ZXing y da la URL exacta | Codificar otra URL → test de ida y vuelta |
| EX3 ventana | Reloj fijo, en los bordes: `inicio − 15 min` válida; un instante antes, `fora_de_hora`; `fin` excluido | `<=` en lugar de `<`, o ventana sin los 15 min → test de bordes |
| EX4/EX5 analítica | Dataset dorado: porcentajes y ranking esperados; empates por hora ascendente; días de cambio horario (23 h y 25 h) | Contar también `cancelada` → test dorado. Quitar el desempate → test de empates |
| EX6 aislamiento (RLS) | Con otro tenant, `reserva_acceso` y la analítica devuelven 0 filas | Quitar la policy → test RLS; `NO FORCE` → `TenantCatalogGuardTest` |
| EX6 privilegios y FK | La app no puede `UPDATE`/`DELETE` en `reserva_acceso` | `GRANT UPDATE`/`DELETE`, FK sin `tenant_id` o `UNIQUE` sin `tenant_id` → un test cada uno |
| Tenant por ruta | `/api;x=1/t/...` no se salta el filtro; en rutas de token manda la variable de ruta, no la cabecera | Resolver por prefijo de ruta → test del bypass |
| Prod fail-closed | Con perfil prod, `POST` de reservar/cancelar y los `GET` de analítica fallan cerrados | Activar el resolver por cabecera en prod → test |
| Mapeo HTTP | 409 / 404 / 400 / 204 según contrato; nunca traza ni SQL | Adaptador sin traducir → test |
| Token fuera de logs | Se captura el log de una petición y el token no aparece | Quitar el enmascarado → test |

### Criterios de parada
- Criterios EX0..EX7 verificados, cada uno con su test de la tabla.
- `V4` aplica limpio sobre BD nueva; `V1`..`V3` intactas (checksums de Flyway).
- `TenantCatalogGuardTest` verde **sin tocar su allowlist**; enforcer verde (`domain`/`application` sin Spring ni JPA).
- `mvn -B clean verify` en verde en local y en CI (self-hosted).
- Cada PR nombra su origen (EX-n / D-011..D-016).
- **`/revision-adversarial <PR>`** antes de mergear PR-A y PR-B (tocan esquema y seguridad), mientras ai-review esté pausada.
- **Sin merge a `main` sin validación del/de la mantenedor(a).**

### NO pulir
- UI y front React; estilo del QR (logo, colores).
- Propiedades `.ics` extra (`VALARM`, `LOCATION`, `DESCRIPTION` rica).
- Más de 3 recomendaciones, o un ranking que no sea ocupación histórica.
- Optimización más allá del `EXPLAIN` con volumen; paginación; i18n de los mensajes de error.

## 7. RESTRICCIONES / no-gos
- **Esquema y código aceptado:** no se tocan `V1`..`V3`, ni `pista`/`reserva`, ni los casos de uso `ReservarPista`/`CancelarReserva` (solo se invocan o se amplían con `EmitirAcceso` en su misma transacción).
- **Git:** PRs independientes desde `origin/main` limpio, nunca apilados. Sin merge a `main` sin validación. Keywords de cierre solo en el cuerpo del PR y en la última línea del squash.
- **Dependencias:** ZXing (Apache-2.0) solo en `infrastructure`; ical4j (BSD-3) solo en scope `test`. Ambas permisivas; la compatibilidad con EUPL-1.2 se comprueba en el PR de implementación (T6/T7).
- **Seguridad:** token con `SecureRandom` y 256 bits, comparación en tiempo constante, nunca en logs y nunca devuelto tras la creación. SQL con fragmentos constantes y valores como parámetros. Sin async ni hilos virtuales con acceso a datos.
- **Tiempo:** el reloj va siempre por el puerto `Clock`; prohibido `LocalDateTime.now()`/`Instant.now()` directo en dominio y aplicación.
- **Tests:** cada clase de integración con su `@DynamicPropertySource` y su contexto; el perfil `test` mantiene el pool de Hikari limitado.
- **Prioridad si el plazo aprieta:** primero EX0..EX3 (el token y lo que cuelga de él), después EX4 y por último EX5. Se recorta alcance, **nunca** tests ni mutaciones.
- **Señalar, no arreglar:** cualquier hallazgo fuera de esta spec va a un issue.

## 8. PLAN DE EJECUCIÓN
‖ = paralelizable lógicamente. Como solo hay una sesión con escritura por repo, se ejecutan en serie; cada PR sale de `origin/main` limpio.

| ID | Tarea | Origen | Depende de |
|---|---|---|---|
| **T0** | **PR de la spec:** este fichero + D-011..D-016 en `docs/decisiones.md` (`chore/spec-extras`) | §1..§7, D-011..D-016 | — |
| **PR-A: token y API base** | | | |
| T1 | `V4__reserva_acceso.sql` (RLS, FORCE, GRANT sin UPDATE/DELETE, FK y UNIQUE con `tenant_id`) + tests de BD y sus mutaciones | EX0, EX6, D-011 | T0 mergeada |
| T2 | `GeneradorToken` (SecureRandom + SHA-256) y `EmitirAcceso` en la misma transacción que `ReservarPista`; tests de hash y atomicidad | EX0, EX7, D-011 | T1 |
| T3 | REST mínima: `POST` reservar (201 + token una vez) y cancelar, `problem+json`, prod fail-closed | EX0, D-012 | T2 |
| **PR-B: lo que cuelga del token** (desde `main` con PR-A mergeado) | | | |
| T4 | Resolver de tenant por variable de ruta, 404 uniforme, token fuera de logs, test del bypass `/api;x=1/` | EX0, D-014 | T3 |
| T5 ‖ | `ValidarAcceso` + ventana + puerto `Clock` + `GET acceso/{token}`, con tests de bordes | EX3, D-015 | T4 |
| T6 ‖ | `IcsWriter` + `GET calendario.ics` (oráculo ical4j) + comprobar licencias | EX1, D-013 | T4 |
| T7 ‖ | `QrZxing` + `GET qr.png` + `pistaberta.public-base-url` (ida y vuelta con lector) | EX2 | T4 |
| **PR-C: analítica** (independiente de A y B) | | | |
| T8 | Consulta de ocupación (`generate_series` en `Europe/Madrid`, dataset dorado, cambio horario, aislamiento por tenant) + `GET ocupacion` | EX4, D-016 | T0 mergeada |
| T9 | `RecomendadorFranxas` (dominio puro, desempate por hora) + `GET recomendacion` | EX5, D-016 | T8 |
| T10 ‖ | `EXPLAIN` con 200.000 filas: confirmar que usa el índice existente, sin añadir otro | EX4 | T8 |
| **Cierre** | | | |
| T11 | `/revision-adversarial <PR>` en PR-A y PR-B antes de mergear | regla global | PR-A / PR-B abiertos |
| T12 ‖ | Hoja de ruta de la agenda multi-reserva en docs | D-013 | T0 |

**Orden por prioridad de plazo:** PR-A → PR-B → PR-C. Si falta tiempo, se recorta T9 primero y después T8..T10, sin recortar tests.

## 9. Riesgos y límites conocidos
- **R-1 Token al portador:** quien tenga el enlace puede ver la reserva y su QR; es un ticket, no una identidad. Mitigación: 256 bits, `no-store`, sin logs. Revocación y expiración, fuera de alcance.
- **R-2 Cancelación sin control en dev/test:** cualquiera puede cancelar por cabecera hasta la spec de auth.
- **R-3 Recomendación sin horario de apertura:** se infiere del histórico; sin datos, lista vacía.
- **R-4 Respuesta del `POST` perdida:** el token no se recupera (D-015).
- **R-5 Demo vs. prod:** en prod los `POST` y los `GET` de analítica no existen hasta la spec de auth; la candidatura se demuestra en `dev`.
- **R-6 Plazo:** 9 días desde la fecha de esta spec; ver prioridad en §7.
