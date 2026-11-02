# Arquitectura

Notas de enxeñaría de PistaAberta: que se decidiu, por que e como se comproba que segue sendo certo. Cada decisión está rexistrada en [docs/decisiones.md](docs/decisiones.md) (D-001 a D-010) e desenvolvida nas especificacións de [docs/](docs/).

## 1. Hexagonal / DDD

```
pistaberta-domain  ←  pistaberta-application  ←  pistaberta-infrastructure
 (regras puras)       (casos de uso e portos)     (Spring, JDBC, Flyway, HTTP)
```

- `domain` e `application` **non dependen de Spring nin de JPA**. Non é unha convención: o `maven-enforcer` fai fallar `mvn verify` se alguén importa esas dependencias.
- Os casos de uso (`ReservarPista`, `CancelarReserva`) son Java plano e execútanse dentro do porto `Transaccion`; a infraestrutura implementa ese porto sobre o xestor de transaccións de Spring.
- A infraestrutura traduce os erros da base de datos a erros de dominio. Nunca sae SQL cru cara ao caso de uso.
- Os identificadores van en galego e ASCII (`Franxa`, `SlotNonDisponible`, `PistaNonExiste`); o `ñ` só vive en documentación e textos de usuario.

## 2. Multi-tenant: o illamento non depende dunha soa capa

Cada concello é un tenant. Os datos illanse con **dúas defensas obrigatorias, ningunha substitúe á outra**:

1. **Filtro aplicativo.** Todo acceso a datos pasa por `TenantScopedJdbc`, que engade o filtro por tenant, impide reasignar `tenant_id` e lanza `TenantNotSetException` se non hai tenant.
2. **Row Level Security de PostgreSQL.** Toda táboa de datos leva `tenant_id`, `ENABLE` + `FORCE ROW LEVEL SECURITY` e unha policy sobre `app_tenant_id()`. Se un filtro se esquece, a base de datos devolve cero filas.

Regras que fan que isto aguante:

- **Falla pechada.** O tenant fíxase con `set_config(..., true)` ao comezar a transacción (`TenantAwareTransactionManager`). Fóra dunha transacción non hai tenant e a base de datos non devolve nada. Está prohibido o parámetro de sesión, porque filtraría entre conexións do pool (hai un test que o proba).
- **Todo acceso a datos vai dentro de transacción.**
- **A aplicación non pode saltarse RLS.** Conéctase como `pistaberta_app`, sen superuser nin `BYPASSRLS`; Flyway migra co propietario. A migración V2 falla se o rol existe con eses privilexios e `AppRoleGuard` impide arrancar con un rol que se salte RLS.
- **Deny by default.** `TenantFilter` cobre **toda** petición, non só `/api/**`: filtrar por prefixo de ruta saltábase con `/api;x=1/...`.
- **RLS non é protección contra inxección SQL** (o rol da aplicación pode fixar o parámetro). Por iso os fragmentos SQL son constantes e os valores van sempre como parámetros.
- **Unicidades e claves foráneas levan `tenant_id`**, tamén as compostas, porque as comprobacións de FK e de exclusión non pasan por RLS: unha clave primaria global filtraría a existencia dun id doutro concello.
- **Guard de catálogo.** `TenantCatalogGuardTest` revisa *todas* as táboas de `public`: ou levan RLS forzada e policy, ou están na súa lista de catálogos globais, e engadir unha esixe tocar o test. Unha táboa nova sen illamento non pasa a compilación.
- En produción non hai resolución de tenant ata que exista a especificación de autenticación; a cabeceira `X-Tenant-Id` só existe nos perfís `dev` e `test`.

## 3. Reservas: a invariante en dúas capas

> Unha pista non pode ter dúas reservas **confirmadas** con franxas que se solapen.

| Capa | Mecanismo | Para que serve |
|---|---|---|
| Dominio | `DisponibilidadePista` → `SlotNonDisponible` | Falla rápido e cunha mensaxe clara |
| Base de datos | `EXCLUDE USING gist (tenant_id WITH =, pista_id WITH =, tstzrange(inicio, fin) WITH &&) WHERE (estado = 'confirmada')` | Única barreira que resiste á concorrencia e a un erro de código |

Detalles que importan:

- Intervalo semiaberto `[inicio, fin)`: as franxas adxacentes non solapan.
- A constraint é **parcial**: cancelar libera a franxa, e as canceladas consérvanse como histórico.
- `CHECK (fin > inicio)` (a exclusión non frea o rango baleiro); a `Franxa` normalízase a microsegundos para que Java (nanosegundos) e PostgreSQL coincidan no bordo.
- `tenant_id` vai de primeiro na constraint e no índice.
- Mínimo privilexio: a aplicación non ten `DELETE` sobre `reserva` e só `SELECT` sobre `pista`; `UPDATE` só sobre a columna `estado`. A franxa, a pista e o tenant son inmutables para ela.
- `cancelada` é terminal: un trigger (`reserva_non_reabrir`) prohibe `cancelada → confirmada` para calquera rol e aínda con a franxa libre. Para volver reservar créase unha reserva nova.
- A reserva cancélase, non se borra.
- A concorrencia resólvea a base de datos: a segunda transacción **bloquéase** ata que a primeira remata; se esta confirma, a segunda falla con `23P01`; se fai rollback, triunfa.
- Tradución de erros: `23P01` + `reserva_sen_solape` → `SlotNonDisponible`; `23503` + `reserva_pista_fk` → `PistaNonExiste`. Calquera outro erro propágase tal cal, para non ocultar fallos inesperados.

## 4. Filosofía de probas

**Un verde non pode ser mudo.** Un test que nunca falla non demostra nada; por iso cada regra ten:

1. un test que **morde**: falla cando a regra se rompe, e
2. unha **mutación illada**: un cambio mínimo que desactiva só esa regra e que o test debe detectar.

Prohibir tamén se verifica: que a aplicación *non poida* borrar unha reserva, ou mover a súa franxa, compróbase co rol real da aplicación contra PostgreSQL.

Como se aplica:

- **Mutación por tier.** Distínguense tres niveis, e cada mutación toca un só: dominio (p. ex. `comprobarLibre` sen efecto, `solapaCon` con `<=`, `Franxa` sen normalizar), base de datos (quitar a constraint, rango `'[]'`, constraint sen `WHERE`, FK sen `tenant_id`, `GRANT DELETE`, `NO FORCE`, `GRANT UPDATE` de táboa enteira, sen o trigger, PK global) e adaptador (sen tradución de erros). Unha mutación pode tombar varios tests da *mesma* regra; o que non debe é tombar regras alleas.
- **Defensa en profundidade verificada.** Anular a capa de dominio non rompe a proba de integración, porque a constraint pilla o solape e o adaptador tradúceo ao mesmo erro: é a propiedade das dúas capas, non un oco. Quitar a constraint tomba, en cambio, a proba de BD, a de concorrencia e a de tradución: tres caras da mesma barreira.
- **A concorrencia só conta se se proba o bloqueo.** O test de concorrencia consulta `pg_stat_activity` (`wait_event_type = 'Lock'`) para demostrar que a segunda transacción queda realmente bloqueada; un test verde sen esa comprobación non proba que a base de datos serialice.
- **Medir antes de mitigar.** Primeiro créase o esquema *sen* a constraint e comprobase que os tests poñen o vermello; só entón engádese a barreira.
- **Os tests do caso límite deixan a franxa libre.** Se estivese ocupada, a constraint frearía a operación e o test non probaría o trigger.
- **Probas contra PostgreSQL real** (Testcontainers), nunca contra unha base simulada: RLS, exclusión e privilexios por columna non existen nun mock.
- **Mutacións sempre con `mvn clean verify`.** Sen `clean`, `target/` conserva clases e migracións xa borradas e dá un falso verde. A CI executa tamén `mvn -B clean verify`.

## 5. Evolución do esquema

- **Flyway-first.** O esquema só cambia por migracións `V<n>__*.sql`; nunca `ddl-auto=update`; nunca se edita unha migración xa mergeada.
- Cada migración concede de forma explícita os privilexios DML ao rol da aplicación (non hai `ALTER DEFAULT PRIVILEGES`), para que unha táboa sen RLS non quede escribible por defecto.
- Os nomes das constraints son literais en SQL e no adaptador; unha divergencia cázana as mutacións e os tests correspondentes.

## 6. Límites coñecidos

- A autenticación, a resolución de tenant en produción e a API REST de reservas non están feitas; hai unha especificación previa a cada unha.
- Accesos asíncronos ou con fíos virtuais con datos: fóra de alcance ata a súa especificación (o tenant vai ligado á transacción).
- `pista` é un esqueleto: o seu identificador é global e deberá ser `(tenant_id, id)` cando se faga o catálogo (rexistrado como ítem coñecido en [docs/decisiones.md](docs/decisiones.md)).
