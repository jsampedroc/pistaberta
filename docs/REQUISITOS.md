# Matriz de requisitos — I Certame GOBTEC (bases v3)

Origen: bases v3 (modificadas el 2026-10-20), puntos 1.1, 1.2 y 1.3, y decisiones D-017..D-019. Plazo: **2026-11-18 23:59**, por correo a `certame@agasol.gal`. Este fichero es normativo para el alcance; el estado se mide contra `origin/main`.

Leyenda de estado: **HECHO** (hay código y test que lo prueba, con puntero), **ESPEC.** (especificado, sin implementar), **PENDIENTE** (sin spec), **DOC** (se cubre con documento, sin código).

## 1.1 Alcance funcional mínimo (obligatorio)

| ID | Requisito | Estado | Puntero / siguiente paso |
|---|---|---|---|
| C1 | Ciudadanía: consultar instalaciones | PENDIENTE | Catálogo de pistas (spec nueva). `pista` es un stub (`V3__pista_reserva.sql`) |
| C2 | Ciudadanía: reserva online | HECHO | `ReservarPista`, `ReservaExclusionConstraintTest`, `ReservaConcurrenciaTest` (RV4) |
| C3 | Ciudadanía: modificar y cancelar | HECHO (cancelar) · PENDIENTE (modificar) | `CancelarReserva`, `ReservaCancelacionTest`; modificar: spec nueva |
| C4 | Ciudadanía: historial y avisos | PENDIENTE | Spec nueva (necesita identidad de solicitante, ver T3) |
| M1 | Concello: alta y mantenimiento de instalaciones | PENDIENTE | Catálogo (misma spec que C1) |
| M2 | Concello: horarios y normas de uso | PENDIENTE | Spec nueva, tras el catálogo |
| M3 | Concello: indicadores básicos | ESPEC. | `docs/SDD-extras.md` EX4/EX5 (D-016) |
| R1 | Reutilización: replicable y configurable | PENDIENTE | Configuración por concello, tras M2 |
| R2 | Reutilización: escalable | HECHO | Postgres estándar, Flyway, sin servicios externos (`README.md`) |
| R3 | Reutilización: multi-municipio | HECHO | `TenantRowLevelSecurityTest`, `TenantScopedJdbcTest`, `TenantPoolIsolationTest`, `TenantCatalogGuardTest` |

## 1.2 Condicionantes técnicos (obligatorios)

| ID | Requisito | Estado | Puntero / siguiente paso |
|---|---|---|---|
| T1 | Software libre (OSI/FSF) | HECHO | `LICENSE` EUPL-1.2 (recomendada en el punto 8 de las bases) |
| T2 | Web abierta, responsive y modular | PENDIENTE | Front React 18 + TS PWA (D-018); backend modular ya hecho (`ARCHITECTURE.md`) |
| T3 | Interoperabilidad: autenticación pública y estándares abiertos | PENDIENTE | OIDC con Keycloak (D-017); spec de auth. Estándares: `.ics` y QR en `docs/SDD-extras.md` |
| T4 | RXPD y gestión segura de usuarios | DOC + PENDIENTE | Documento de RXPD; usuarios llegan con T3. Aislamiento ya probado (R3) |
| T5 | Accesibilidad WCAG 2.1 AA (RD 1112/2018, EN 301549) | DOC + PENDIENTE | Criterios y declaración como docs; axe en e2e cuando exista el front (D-018) |

## 1.3 Valorables (opcionales)

| ID | Requisito | Estado | Puntero / siguiente paso |
|---|---|---|---|
| V1 | Calendarios personales (`.ics`) | ESPEC. | `docs/SDD-extras.md` EX1 (D-013) |
| V2 | Acceso por código QR | ESPEC. · parcial | Token de acceso hecho (`ReservarPistaConAccesoIntegracionTest`); QR y validación: EX2/EX3 (D-015) |
| V3 | Analítica de uso | ESPEC. | `docs/SDD-extras.md` EX4 (D-016) |
| V4 | Recomendación horaria | ESPEC. | `docs/SDD-extras.md` EX5 (D-016) |
| V5 | App móvil | PENDIENTE | PWA sobre el front (D-018) |
| V6 | Avisos (notificaciones) | PENDIENTE | Spec nueva; recorte prioritario si el plazo aprieta (D-019) |

## Orden de ejecución

PRs independientes desde `origin/main` limpio, nunca apilados; spec aprobada antes de código en cada dominio nuevo. Orden: (1) este PR; (2) catálogo, horarios y normas (C1, M1, M2, R1); (3) modificar reserva e historial (C3, C4); (4) auth OIDC (T3, T4); (5) front PWA (T2, T5, V5); (6) extras (V1..V4, V6). La regla de recorte es D-019.

## Entrega (bases, punto 3 y 4)

- Memoria técnica en PDF, máximo 10 páginas, sobre la plantilla oficial ODT. Sin empezar.
- Datos de contacto de la entidad en el cuerpo del correo.
- Elegibilidad: solo entidades con actividad económica o proyecto empresarial, domicilio o centro de trabajo en Galicia, una propuesta por entidad. **Pendiente de confirmar por Pe.**
