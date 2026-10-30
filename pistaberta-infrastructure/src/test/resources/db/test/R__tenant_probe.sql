-- Tabla de datos mínima SOLO de test para probar el aislamiento (sin lógica de reservas).
CREATE TABLE IF NOT EXISTS tenant_probe (
    id        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenant (id),
    etiqueta  text NOT NULL
);
CREATE INDEX IF NOT EXISTS tenant_probe_tenant_id_idx ON tenant_probe (tenant_id);
GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_probe TO pistaberta_app;

-- RLS (D-003): la BD impone el aislamiento aunque la query no filtre.
ALTER TABLE tenant_probe ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_probe FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON tenant_probe;
CREATE POLICY tenant_isolation ON tenant_probe
    USING (tenant_id = app_tenant_id())
    WITH CHECK (tenant_id = app_tenant_id());
