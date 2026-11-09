package org.pistaberta.infrastructure.reserva;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.pistaberta.application.reserva.ReservaRepository;
import org.pistaberta.domain.reserva.EstadoReserva;
import org.pistaberta.domain.reserva.Franxa;
import org.pistaberta.domain.reserva.FranxaInvalida;
import org.pistaberta.domain.reserva.PistaId;
import org.pistaberta.domain.reserva.PistaNonExiste;
import org.pistaberta.domain.reserva.Reserva;
import org.pistaberta.domain.reserva.ReservaId;
import org.pistaberta.domain.reserva.SlotNonDisponible;
import org.pistaberta.infrastructure.tenant.TenantScopedJdbc;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * Adaptador JDBC del puerto de reservas. Todo pasa por TenantScopedJdbc (filtro por tenant) y debe ejecutarse
 * dentro de una transacción. Traduce las violaciones de la BD a errores de dominio (D-009): nunca sale SQL crudo.
 */
@Component
public class JdbcReservaRepository implements ReservaRepository {

    static final String CONSTRAINT_SOLAPE = "reserva_sen_solape";
    static final String CONSTRAINT_PISTA = "reserva_pista_fk";
    private static final String EXCLUSION_VIOLATION = "23P01";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    /**
     * Rango de franxas que acepta la persistencia. Hace falta una guarda explícita: el driver convierte en silencio las
     * fechas muy anteriores en {@code -infinity} (se guardaría una reserva ilimitada por el pasado) y Postgres rechaza
     * las posteriores al año 294276 con un error crudo. Los límites quedan lejos de ambos y son los años ISO de 4 dígitos.
     */
    static final Instant FRANXA_MIN = Instant.parse("1900-01-01T00:00:00Z");
    static final Instant FRANXA_MAX = Instant.parse("9999-12-31T23:59:59Z");

    private static final RowMapper<Reserva> MAPPER = (rs, i) -> Reserva.reconstruir(
            new ReservaId(rs.getObject("id", UUID.class)),
            new PistaId(rs.getObject("pista_id", UUID.class)),
            new Franxa(rs.getObject("inicio", OffsetDateTime.class).toInstant(),
                    rs.getObject("fin", OffsetDateTime.class).toInstant()),
            deBd(rs.getString("estado")));

    private final TenantScopedJdbc jdbc;

    public JdbcReservaRepository(TenantScopedJdbc jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Reserva> activasSolapadas(PistaId pista, Franxa franxa) {
        comprobarRango(franxa);
        return jdbc.query("""
                select tenant_id, id, pista_id, inicio, fin, estado
                from reserva
                where pista_id = :pista and estado = 'confirmada'
                  and tstzrange(inicio, fin) && tstzrange(cast(:inicio as timestamptz), cast(:fin as timestamptz))
                """,
                Map.of("pista", pista.value(), "inicio", utc(franxa.inicio()), "fin", utc(franxa.fin())),
                MAPPER);
    }

    @Override
    public void gardar(Reserva reserva) {
        comprobarRango(reserva.franxa());
        try {
            jdbc.insert("reserva", Map.of(
                    "id", reserva.id().value(),
                    "pista_id", reserva.pistaId().value(),
                    "inicio", utc(reserva.franxa().inicio()),
                    "fin", utc(reserva.franxa().fin()),
                    "estado", paraBd(reserva.estado())));
        } catch (DataAccessException e) {
            throw traducir(e, reserva);
        }
    }

    @Override
    public Optional<Reserva> buscar(ReservaId id) {
        return jdbc.query("select tenant_id, id, pista_id, inicio, fin, estado from reserva where id = :id",
                Map.of("id", id.value()), MAPPER).stream().findFirst();
    }

    @Override
    public void actualizar(Reserva reserva) {
        try {
            jdbc.update("reserva", Map.of("estado", paraBd(reserva.estado())), "id = :id",
                    Map.of("id", reserva.id().value()));
        } catch (DataAccessException e) {
            throw traducir(e, reserva);
        }
    }

    private static void comprobarRango(Franxa franxa) {
        if (franxa.inicio().isBefore(FRANXA_MIN) || franxa.fin().isAfter(FRANXA_MAX)) {
            throw new FranxaInvalida("A franxa debe estar entre " + FRANXA_MIN + " e " + FRANXA_MAX);
        }
    }

    /** SQLSTATE 23P01 + constraint reserva_sen_solape → SlotNonDisponible; 23503 + reserva_pista_fk → PistaNonExiste. */
    private static RuntimeException traducir(DataAccessException e, Reserva reserva) {
        for (Throwable causa = e; causa != null; causa = causa.getCause()) {
            if (causa instanceof PSQLException psql && psql.getServerErrorMessage() != null) {
                String estado = psql.getSQLState();
                String constraint = psql.getServerErrorMessage().getConstraint();
                if (EXCLUSION_VIOLATION.equals(estado) && CONSTRAINT_SOLAPE.equals(constraint)) {
                    return new SlotNonDisponible(reserva.franxa(), e);
                }
                if (FOREIGN_KEY_VIOLATION.equals(estado) && CONSTRAINT_PISTA.equals(constraint)) {
                    return new PistaNonExiste(reserva.pistaId(), e);
                }
            }
        }
        return e;
    }

    private static OffsetDateTime utc(Instant instante) {
        return OffsetDateTime.ofInstant(instante, ZoneOffset.UTC);
    }

    private static String paraBd(EstadoReserva estado) {
        return switch (estado) {
            case CONFIRMADA -> "confirmada";
            case CANCELADA -> "cancelada";
        };
    }

    private static EstadoReserva deBd(String estado) {
        return switch (estado) {
            case "confirmada" -> EstadoReserva.CONFIRMADA;
            case "cancelada" -> EstadoReserva.CANCELADA;
            default -> throw new IllegalStateException("Estado de reserva desconocido en la BD: " + estado);
        };
    }
}
