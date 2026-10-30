# SDD — Multi-tenant (concello = tenant)

Estado: **PROPUESTA PARA VISTO DE PE** · no hay código de implementación en este PR.
Normativo: `CLAUDE.md` y `docs/decisiones.md` (D-003 a D-006 nacen de esta spec).

## 0. Meta
- **Proyecto:** PistaAberta (I Certame GOBTEC) · repo `jsampedroc/pistaberta`
- **Fecha:** 2026-10-30
- **Tipo:** módulo (fundamento transversal)
- **Target:** `origin/main` vía `feat/multi-tenant` (PR de implementación aparte, autónomo, sin stacking)
- **Aterrizado contra `origin/main@10fe123`:** módulos `pistaberta-domain` ← `pistaberta-application` ← `pistaberta-infrastructure`;
  `pistaberta-infrastructure/src/main/resources/db/migration/V1__init_postgis_and_version.sql` (solo `app_version`);
  `application.yml` con `spring.datasource.*` (`DB_URL`/`DB_USER`/`DB_PASSWORD`) y `spring.flyway.locations: classpath:db/migration`;
  `pistaberta-infrastructure/src/test/java/org/pistaberta/infrastructure/ApplicationContextTest.java` (Testcontainers `postgis/postgis:16-3.4`);
  infrastructure solo tiene `spring-boot-starter-jdbc` (sin web).

## 1. OBJETIVO
Cada concello es un tenant con datos aislados: una operación en el contexto del concello A no puede leer ni modificar datos del concello B.
Este módulo aporta el mecanismo de aislamiento y su prueba antes de que exista ningún dato de dominio, para que el dominio de reservas nazca ya aislado.
No incluye reservas ni autenticación de usuarios. El aislamiento se sostiene también frente a un bug de código aplicativo (defensa en profundidad)
y se verifica con tests de integración que fallan si se quita cada capa.

## 2. QUÉ
### 2.1 In-scope (criterios verificables)
| ID | Criterio | Origen |
|---|---|---|
| C1 | Migración `V2` aplica limpia sobre V1: tabla global `tenant`, función `app_tenant_id()`, rol `pistaberta_app` (idempotente; NOSUPERUSER, NOBYPASSRLS), grants. | D-003, D-004, D-006 |
| C2 | **R1 · RLS:** con rol `pistaberta_app` y GUC tenant=A, una query cruda SIN filtro que pide datos de B devuelve 0 filas; UPDATE/DELETE sobre B afectan 0 filas; INSERT con tenant B falla (SQLSTATE 42501). | D-003 |
| C3 | **R2 · Filtro aplicativo:** en una conexión que bypassa RLS (superuser), el repositorio de la app solo devuelve filas de A. | D-003 |
| C4 | Fail-closed: sin tenant en contexto → 0 filas en BD y excepción en el código. Acceso fuera de transacción → 0 filas. | D-003, Q7 |
| C5 | **R3 · Pool:** el tenant de una transacción no se filtra a la siguiente transacción de la misma conexión física. | D-003 |
| C6 | **R4 · Guard de catálogo:** toda tabla de `public` es un catálogo global conocido (allowlist) o tiene `tenant_id` con RLS enabled, FORCE y ≥1 policy. | D-003 |
| C7 | Header `X-Tenant-Id` (slug) resuelto vía puerto; falta/inválido → 400; concello desconocido → 404; el contexto se limpia al terminar la request. | D-005 |
| C8 | **R5 · Semilla:** `tenant` vacía con el perfil por defecto; 2 concellos solo en perfiles dev/test. | D-005 |
| C9 | `domain` y `application` siguen sin Spring ni JPA; `maven-enforcer` pasa. | CLAUDE.md |
| C10 | `docs/decisiones.md` con D-003..D-006 (este PR) y `CLAUDE.md` con la convención multi-tenant (PR de implementación). | CLAUDE.md |

### 2.2 Out-of-scope (explícito)
- Dominio de reservas e instalaciones.
- Autenticación, usuarios y roles de usuario (JWT/Keycloak): spec propia. Hasta entonces **prod no tiene resolver de tenant** (fail-closed).
- API/UI de alta o baja de concellos (altas solo por migración).
- Acceso cross-tenant de administración/informes (otra spec, otro rol).
- Propagación del tenant a hilos asíncronos o virtuales.
- PgBouncer / pooling externo, backups por tenant, configuración por tenant, frontend.
- Cambios de CI/runner (PR independiente).

## 3. CÓMO
- **Stack:** Java 21 · Spring Boot 3.3 · PostgreSQL 16 + PostGIS · Flyway. Multi-tenancy: **sí**. Auth: ninguna en este módulo. Sin Kafka/Redis/MinIO.
- **Aislamiento (D-003): columna `tenant_id` + filtro aplicativo obligatorio + Row Level Security de Postgres.** Policy
  `USING (tenant_id = app_tenant_id()) WITH CHECK (tenant_id = app_tenant_id())` y `FORCE ROW LEVEL SECURITY`.

  | Alternativa | Valoración |
  |---|---|
  | Solo filtro aplicativo | Barata; un bug de código es una fuga. Descartada. |
  | Solo RLS | No permite probar la capa aplicativa por separado. Descartada. |
  | Esquema por tenant | N migraciones, pool complejo, coste de cambio alto. Descartada. |
  | BD por tenant | Aislamiento máximo, coste operativo alto. Descartada. |

  **Coste de cambiar después:** pasar de columna a esquema/BD por tenant exige reescribir migraciones y mover datos (caro). Quitar RLS y quedarse con filtro es trivial.
  **Coste asumido:** GUC de tenant por transacción, rol de aplicación no privilegiado, dos credenciales (migrador y app), cuidado con el pool.
- **Hexagonal:**
  - `pistaberta-domain`: `TenantId` (record puro).
  - `pistaberta-application`: puerto `TenantContext` (`current()` / `require()`; sin tenant lanza excepción).
  - `pistaberta-infrastructure`: puerto `TenantResolver` + adaptador `HeaderTenantResolver`; filtro servlet que puebla el contexto y lo limpia al terminar;
    repositorio JDBC; `DataSourceTransactionManager` propio que aplica el GUC en `doBegin`. Se añade `spring-boot-starter-web`.
  - Cambiar la fuente del tenant (claim JWT) = otro `TenantResolver`, sin tocar domain, application ni acceso a datos.
- **GUC por sesión y pool.** El tenant se fija con `select set_config('app.tenant_id', ?, true)`: **local a la transacción**. Al hacer commit/rollback Postgres lo descarta,
  así que una conexión devuelta a Hikari nunca arrastra tenant. Prohibido el GUC de sesión (`is_local=false`). Fuera de transacción el GUC queda vacío y
  `app_tenant_id()` devuelve NULL → 0 filas. **Consecuencia: todo acceso a datos va dentro de una transacción.**
- **Dos credenciales.** Flyway usa el owner/migrador (`spring.flyway.user/password`); la app usa `pistaberta_app` (`spring.datasource.*`).
  Owner y superuser saltan RLS, por eso la app jamás se conecta con ellos.
- **Identificador (D-004):** `tenant.id` uuid surrogate + `slug` único (el header lleva el slug) + `codigo_ine` único como atributo, no como clave
  (las fusiones de concellos cambian el código INE).

## 4. MODELO DE DATOS
- **`V2__tenant_isolation.sql` (prod, `db/migration`):**
  - `tenant(id uuid pk, slug text not null unique, codigo_ine char(5) not null unique, nome text not null)`. Catálogo global, **sin RLS**, `SELECT` para `pistaberta_app`. Sin columna `activo`.
  - `app_tenant_id()`: `nullif(current_setting('app.tenant_id', true), '')::uuid`, `STABLE`.
  - Rol `pistaberta_app`: `DO $$ ... IF NOT EXISTS ... CREATE ROLE ... LOGIN NOSUPERUSER NOBYPASSRLS ...`, contraseña por placeholder de Flyway (`DB_PASSWORD`), `GRANT` de uso sobre el esquema y `SELECT` sobre `tenant`. **Revisión adversarial (PR #3):** sin `ALTER DEFAULT PRIVILEGES`; cada tabla futura concede su DML de forma explícita. V2 falla si el rol existe con SUPERUSER o BYPASSRLS.
- **Solo test (`db/test/R__tenant_probe.sql`, repetible e idempotente):** `tenant_probe(id uuid pk, tenant_id uuid not null references tenant(id), etiqueta text not null)`,
  índice `(tenant_id)`, `ENABLE` + `FORCE ROW LEVEL SECURITY` + policy. Sin lógica de reservas.
- **Semilla (`db/seed/R__concellos.sql`, solo dev/test, `ON CONFLICT DO NOTHING`):**

  | slug | nome | codigo_ine |
  |---|---|---|
  | `a-estrada` | A Estrada | 36017 |
  | `lalin` | Lalín | 36024 |

  Códigos INE verificados (2026-10-30): A Estrada 36017 (es.wikipedia «Identificadores», Wikidata P772); Lalín 36024 (Wikidata P772; referencias INE `codMuni=024` de es.wikipedia).
- **Perfiles:** por defecto `spring.flyway.locations=classpath:db/migration`; perfil `dev` añade `classpath:db/seed`; perfil `test` añade `db/seed` y `db/test`.
- **Convención para tablas futuras:** `tenant_id uuid not null`, RLS + FORCE + policy, índice que empiece por `tenant_id`, unicidades y FKs que incluyan `tenant_id`.
  El guard C6 hace fallar el build si una tabla con `tenant_id` no cumple.
- **Existentes:** V1 no se toca. `application.yml` gana credenciales de Flyway y `ApplicationContextTest` inyecta las dos credenciales.

## 5. CONTRATOS / API
### 5.1 Contratos
No hay endpoints de negocio. Contrato de entrada en `/api/**`:

| Elemento | Detalle |
|---|---|
| Header `X-Tenant-Id` | slug del concello; solo dev/test |
| Falta o formato inválido | 400 |
| Slug inexistente en `tenant` | 404 |
| Auth | ninguna en este módulo; prod sin resolver hasta la spec de auth |

Los tests usan un controlador de test (scope test), no un endpoint de producto.

### 5.2 Falsabilidad (todas las mutaciones con `mvn clean verify`, aisladas, revertidas)
| Regla | Test que muerde | Mutación aislada | Rojo esperado |
|---|---|---|---|
| R1 RLS (C2) | Rol `pistaberta_app`, GUC=A, query cruda sin filtro pide B → 0 filas; UPDATE/DELETE 0; INSERT B → 42501 | Quitar policy o `ENABLE RLS` de `tenant_probe` | Solo R1 |
| R2 Filtro (C3) | Conexión superuser, repositorio solo devuelve A | Quitar `WHERE tenant_id` del repositorio | Solo R2 |
| R3 Pool (C5) | Hikari tamaño 1: tx con A, luego tx sin tenant → 0 filas | `set_config(..., false)` en vez de `true` | Solo R3 |
| R4 Guard (C6) | Catálogo: tablas con `tenant_id` tienen RLS forced + policy | `NO FORCE` en `tenant_probe` | Solo R4 |
| R5 Semilla (C8) | Perfil por defecto → `tenant` vacía | Mover la semilla a `db/migration` | Solo R5 |

Tras cada mutación se confirma que el build cae **tras `clean`**; si no cae, la mutación no prueba nada (CLAUDE.md).

**Medir antes de mitigar:**
1. Escribir R1 y R2 contra `tenant_probe` sin policy ni filtro y comprobar que salen **rojos** (la fuga existe).
2. Dentro de R1, asertar que la conexión usa un rol con `rolsuper=false` y `rolbypassrls=false`; si no, R1 sería un verde mudo.
3. Con pool tamaño 1, comprobar que la conexión física se reutiliza antes de dar R3 por válido.
4. `EXPLAIN` sobre la probe para comprobar que la policy usa el índice por `tenant_id`.

## 6. CUÁNDO PARAR (DoD)
- C1..C10 verificados; `mvn -B clean verify` en verde (bucle local documentado en el PR).
- Las 5 mutaciones (R1..R5) demostradas en rojo y revertidas, con la evidencia en el PR.
- Migración V2 aplica limpia sobre BD vacía.
- Sin merge a `main` sin validación de Pe.
- **NO pulir:** nombres de concellos más allá de la semilla, rendimiento fino, UI, métricas, documentación de usuario.

## 7. RESTRICCIONES / no-gos
- No editar V1; Flyway-first; nunca `ddl-auto`.
- La app nunca se conecta como owner ni superuser (salvo la conexión de test de R2).
- Prohibido el GUC de sesión.
- El tenant nunca sale de body ni query: solo del puerto `TenantResolver`.
- Sin queries nativas fuera de una transacción con tenant.
- Domain y application puros (sin Spring/JPA).
- PR autónomo desde `origin/main`, sin stacking.

### Riesgos y notas de implantación
- **R-1 · `CREATEROLE` (D-006):** en Postgres gestionado (RDS, Supabase, hosting municipal) el migrador puede NO tener `CREATEROLE`. Entonces ops pre-aprovisiona `pistaberta_app` y la migración solo hace `GRANT`. No se implementa ahora; V2 es idempotente para no romper si el rol existe.
- **R-2 · Header falsificable:** sin auth, cualquier cliente puede declarar cualquier concello. Por eso el resolver por header solo existe en dev/test y prod no tiene resolver (todas las requests a `/api/**` fallan cerradas) hasta la spec de auth.
- **R-3 · Hilos:** el tenant va en el hilo de la request; async y virtual threads quedan fuera y no deben usarse con acceso a datos hasta su spec.
- **R-4 · Probe solo en test:** prod no tendrá ninguna tabla con RLS hasta el dominio de reservas; el guard C6 vigila que las futuras la lleven.
- **R-6 · RLS no defiende de inyección SQL:** el rol de la app puede fijar el GUC por su cuenta; la capa RLS protege frente a filtros olvidados, no frente a SQL concatenado. Todo SQL parametrizado (hallazgo F6 de la revisión adversarial).
- **R-5 · Cambio de semántica de variables:** `DB_USER`/`DB_PASSWORD` pasan a ser la app (`pistaberta_app`); el migrador usa variables nuevas (nombres en T1). Afecta a `docker-compose.yml`, README y CI.

## 8. PLAN DE EJECUCIÓN
Una tarea sin origen sobra. `‖` = paralelizable con la tarea indicada.

| ID | Tarea | Origen | Depende de |
|---|---|---|---|
| T1 | Config: `spring-boot-starter-web`; credenciales de Flyway vs app en `application.yml`; placeholder de contraseña; adaptar `ApplicationContextTest` y `docker-compose.yml`. | C1, Q10, R-5 | — |
| T2 | `TenantId` (domain) y puerto `TenantContext` (application), con tests unitarios; enforcer sigue verde. ‖ T1 | C9, C4 | — |
| T3 | `V2__tenant_isolation.sql`: `tenant`, `app_tenant_id()`, rol idempotente, grants. | C1, D-003, D-004, D-006 | T1 |
| T4 | Migraciones repetibles `db/test/R__tenant_probe.sql` y `db/seed/R__concellos.sql`; `flyway.locations` por perfil. | C8, §4 | T3 |
| T5 | **Medición:** tests R1 y R2 contra la probe sin policy ni filtro; confirmar rojo y guard de rol del §5.2.2. | §5.2 medir | T4 |
| T6 | RLS en la probe (ENABLE + FORCE + policy) y `DataSourceTransactionManager` propio con `set_config(..., true)` en `doBegin`. | C2, C4, C5, D-003 | T5 |
| T7 | `TenantContext` (infra) + repositorio JDBC con filtro obligatorio; fail-closed. ‖ T6 | C3, C4 | T2, T4, T5 |
| T8 | `TenantResolver` + `HeaderTenantResolver` + filtro servlet (400/404, limpieza de contexto), solo dev/test; controlador de test. ‖ T6, T7 | C7, D-005 | T2, T3 |
| T9 | Tests R3 (pool), R4 (guard de catálogo), R5 (semilla). | C5, C6, C8 | T6, T7 |
| T10 | Ejecutar las 5 mutaciones con `mvn clean verify`, una a una, revertir y adjuntar evidencia al PR. | §5.2, DoD | T6..T9 |
| T11 | Docs: sección multi-tenant en `CLAUDE.md` (todo acceso a datos dentro de transacción; convención de tablas con RLS); README (variables y roles). ‖ T9 | C10, Q7 | T6 |
| T12 | Bucle local `mvn -B clean verify` en verde; PR `feat/multi-tenant` con origen D-003..D-006 y mutaciones; **sin merge** hasta visto de Pe. | DoD | T1..T11 |

## 9. Decisiones resueltas (gate de clarificación)
Todas las ambigüedades quedaron resueltas por Pe el 2026-10-30 y pasan a `docs/decisiones.md`: Q2 → D-003 · Q1 → D-004 · Q3, Q4, Q8 → D-005 · Q5, Q6, Q7 → D-006 · Q9 fuera de alcance · Q10, Q11, Q12 y Q13 recogidas en este documento.
Decisiones abiertas para Pe: **ninguna**; solo falta su visto a esta spec.
