-- Mecanismo multi-tenant (D-003..D-006): catálogo de concellos, función del GUC y rol de aplicación.

-- Rol de la app: no privilegiado y sujeto a RLS. Idempotente: si ops ya lo pre-aprovisionó, no se toca.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pistaberta_app') THEN
        CREATE ROLE pistaberta_app LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE
            PASSWORD '${app_db_password}';
    END IF;
END
$$;

-- Un rol pre-aprovisionado por ops puede traer SUPERUSER/BYPASSRLS: saltaría RLS sin que nada fallara.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pistaberta_app' AND (rolsuper OR rolbypassrls)) THEN
        RAISE EXCEPTION 'El rol pistaberta_app no puede ser SUPERUSER ni BYPASSRLS: saltaría RLS (D-003)';
    END IF;
END
$$;

CREATE TABLE tenant (
    id         uuid     PRIMARY KEY,
    slug       text     NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    codigo_ine char(5)  NOT NULL UNIQUE CHECK (codigo_ine ~ '^[0-9]{5}$'),
    nome       text     NOT NULL
);

-- Tenant de la transacción en curso; NULL si no hay (la policy no deja pasar ninguna fila).
CREATE FUNCTION app_tenant_id() RETURNS uuid
    LANGUAGE sql STABLE
    AS $$ SELECT nullif(current_setting('app.tenant_id', true), '')::uuid $$;

GRANT USAGE ON SCHEMA public TO pistaberta_app;
GRANT SELECT ON tenant TO pistaberta_app;
GRANT EXECUTE ON FUNCTION app_tenant_id() TO pistaberta_app;

-- Sin ALTER DEFAULT PRIVILEGES: cada tabla nueva concede DML a la app de forma explícita en su migración,
-- para que una tabla sin RLS no quede escribible por defecto (el guard de catálogo revisa todas las tablas).
