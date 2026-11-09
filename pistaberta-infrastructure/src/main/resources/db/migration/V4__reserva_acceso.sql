-- Extras GOBTEC (docs/SDD-extras.md, D-011): acceso por token opaco a una reserva.
-- El token en claro solo existe en la respuesta de creación; aquí solo su SHA-256.
-- Tabla aparte: `reserva` sigue inmutable para la app (D-010).

CREATE TABLE reserva_acceso (
    tenant_id   uuid        NOT NULL REFERENCES tenant (id),
    reserva_id  uuid        NOT NULL,
    token_hash  bytea       NOT NULL,
    creado      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT reserva_acceso_pkey       PRIMARY KEY (tenant_id, reserva_id),
    CONSTRAINT reserva_acceso_reserva_fk FOREIGN KEY (tenant_id, reserva_id) REFERENCES reserva (tenant_id, id),
    -- Único por tenant (no global): el tenant llega siempre de la ruta. Sirve también de índice de búsqueda.
    CONSTRAINT reserva_acceso_hash_uq    UNIQUE (tenant_id, token_hash),
    CONSTRAINT reserva_acceso_hash_ck    CHECK (octet_length(token_hash) = 32)
);

ALTER TABLE reserva_acceso ENABLE ROW LEVEL SECURITY;
ALTER TABLE reserva_acceso FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON reserva_acceso
    USING (tenant_id = app_tenant_id())
    WITH CHECK (tenant_id = app_tenant_id());

-- Mínimo privilegio: los tokens son inmutables (sin revocar ni regenerar, D-011) y no se borran.
GRANT SELECT, INSERT ON reserva_acceso TO pistaberta_app;
