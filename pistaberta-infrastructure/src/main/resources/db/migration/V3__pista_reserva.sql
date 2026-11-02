-- Reservas (D-007..D-009, docs/SDD-reservas.md): modelo mínimo tenant-scoped de pista y reserva.
-- btree_gist es una extensión "trusted": la crea el dueño de la BD. IF NOT EXISTS por si ops la pre-aprovisiona.
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE pista (
    id         uuid PRIMARY KEY,
    tenant_id  uuid NOT NULL REFERENCES tenant (id),
    nome       text NOT NULL,
    -- Destino de la FK compuesta de reserva; su índice empieza por tenant_id.
    CONSTRAINT pista_tenant_id_uq UNIQUE (tenant_id, id)
);

CREATE TABLE reserva (
    id         uuid NOT NULL,
    tenant_id  uuid NOT NULL REFERENCES tenant (id),
    pista_id   uuid NOT NULL,
    inicio     timestamptz NOT NULL,
    fin        timestamptz NOT NULL,
    estado     text NOT NULL,
    -- PK compuesta (convención CLAUDE.md: unicidades con tenant_id): un id de otro tenant no colisiona ni revela existencia.
    CONSTRAINT reserva_pkey PRIMARY KEY (tenant_id, id),
    -- Compuesta: una reserva no puede apuntar a la pista de otro tenant (los chequeos de FK no pasan por RLS).
    CONSTRAINT reserva_pista_fk  FOREIGN KEY (tenant_id, pista_id) REFERENCES pista (tenant_id, id),
    CONSTRAINT reserva_estado_ck CHECK (estado IN ('confirmada', 'cancelada')),
    -- La exclusion no frena el rango vacío (fin = inicio): lo frena este CHECK.
    CONSTRAINT reserva_franxa_ck CHECK (fin > inicio)
);

-- La joya (D-007): una pista no puede tener dos reservas CONFIRMADAS con franxas que se solapen.
-- tstzrange(inicio, fin) es [) por defecto: las franxas adyacentes no solapan. Parcial: cancelar libera la franxa.
-- tenant_id va primero (D-008): las comprobaciones de exclusión no pasan por RLS y el índice empieza por tenant_id.
ALTER TABLE reserva ADD CONSTRAINT reserva_sen_solape EXCLUDE USING gist
    (tenant_id WITH =, pista_id WITH =, tstzrange(inicio, fin) WITH &&)
    WHERE (estado = 'confirmada');

CREATE INDEX reserva_tenant_pista_inicio_idx ON reserva (tenant_id, pista_id, inicio);

-- RLS en ambas tablas (D-003): ENABLE + FORCE + policy.
ALTER TABLE pista ENABLE ROW LEVEL SECURITY;
ALTER TABLE pista FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON pista
    USING (tenant_id = app_tenant_id())
    WITH CHECK (tenant_id = app_tenant_id());

ALTER TABLE reserva ENABLE ROW LEVEL SECURITY;
ALTER TABLE reserva FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON reserva
    USING (tenant_id = app_tenant_id())
    WITH CHECK (tenant_id = app_tenant_id());

-- Mínimo privilegio (D-008): la app solo lee pista; reserva no se borra, se cancela.
-- UPDATE solo sobre estado: la franxa, la pista y el tenant son inmutables para la app (cancelar = cambiar estado).
GRANT SELECT ON pista TO pistaberta_app;
GRANT SELECT, INSERT ON reserva TO pistaberta_app;
GRANT UPDATE (estado) ON reserva TO pistaberta_app;

-- Transición terminal: una reserva cancelada no vuelve a confirmada (se crea una nueva). Barrera de BD: aplica
-- a cualquier rol y se dispara también con la franxa libre, donde la exclusion constraint no la frenaría.
CREATE FUNCTION reserva_non_reabrir() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'Una reserva cancelada no puede volver a confirmada'
        USING ERRCODE = 'check_violation', CONSTRAINT = 'reserva_non_reabrir';
END
$$;

CREATE TRIGGER reserva_non_reabrir
    BEFORE UPDATE OF estado ON reserva
    FOR EACH ROW
    WHEN (OLD.estado = 'cancelada' AND NEW.estado = 'confirmada')
    EXECUTE FUNCTION reserva_non_reabrir();
