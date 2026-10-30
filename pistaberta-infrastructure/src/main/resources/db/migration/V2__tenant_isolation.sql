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

-- Tablas futuras creadas por el migrador: DML para la app (cada tabla con tenant_id lleva además RLS).
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO pistaberta_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO pistaberta_app;
