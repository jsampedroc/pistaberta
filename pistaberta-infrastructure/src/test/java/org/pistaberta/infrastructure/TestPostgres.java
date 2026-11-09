package org.pistaberta.infrastructure;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Soporte de tests: BD PostGIS real, migrador (superuser del contenedor) y rol de la app. */
public final class TestPostgres {

    public static final String APP_ROLE = "pistaberta_app";
    public static final String APP_PASSWORD = "pistaberta_app";

    public static final UUID A_ESTRADA = UUID.fromString("00000000-0000-4000-8000-000000036017");
    public static final UUID LALIN = UUID.fromString("00000000-0000-4000-8000-000000036024");

    /** Contenedor compartido por todas las clases de test con perfil test (se arranca una vez por JVM). */
    public static final PostgreSQLContainer<?> SHARED = startNew();

    private TestPostgres() {}

    public static PostgreSQLContainer<?> newContainer() {
        return new PostgreSQLContainer<>(
                DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"));
    }

    private static PostgreSQLContainer<?> startNew() {
        PostgreSQLContainer<?> db = newContainer();
        db.start();
        return db;
    }

    /** La app usa el rol pistaberta_app; Flyway usa el superuser del contenedor. */
    public static void register(DynamicPropertyRegistry registry, PostgreSQLContainer<?> db) {
        registry.add("spring.datasource.url", db::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_ROLE);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.url", db::getJdbcUrl);
        registry.add("spring.flyway.user", db::getUsername);
        registry.add("spring.flyway.password", db::getPassword);
        registry.add("spring.flyway.placeholders.app_db_password", () -> APP_PASSWORD);
    }

    /** Conexión superuser: salta RLS; solo para preparar datos y comprobar. */
    public static Connection superuser(PostgreSQLContainer<?> db) throws SQLException {
        return DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
    }

    public static Connection appRole(PostgreSQLContainer<?> db) throws SQLException {
        return DriverManager.getConnection(db.getJdbcUrl(), APP_ROLE, APP_PASSWORD);
    }

    public static void resetProbe(PostgreSQLContainer<?> db, int rowsA, int rowsB) throws SQLException {
        try (Connection c = superuser(db); Statement st = c.createStatement()) {
            st.execute("truncate tenant_probe");
            for (int i = 1; i <= rowsA; i++) {
                st.execute("insert into tenant_probe (tenant_id, etiqueta) values ('" + A_ESTRADA + "', 'a" + i + "')");
            }
            for (int i = 1; i <= rowsB; i++) {
                st.execute("insert into tenant_probe (tenant_id, etiqueta) values ('" + LALIN + "', 'b" + i + "')");
            }
        }
    }

    /** Vacía reservas y pistas con el superuser (salta RLS). */
    public static void resetReservas(PostgreSQLContainer<?> db) throws SQLException {
        try (Connection c = superuser(db); Statement st = c.createStatement()) {
            st.execute("truncate reserva_acceso, reserva, pista");
        }
    }

    public static void insertPista(PostgreSQLContainer<?> db, UUID tenant, UUID pista, String nome) throws SQLException {
        try (Connection c = superuser(db);
                PreparedStatement ps = c.prepareStatement("insert into pista (id, tenant_id, nome) values (?, ?, ?)")) {
            ps.setObject(1, pista);
            ps.setObject(2, tenant);
            ps.setString(3, nome);
            ps.executeUpdate();
        }
    }

    /** Inserción cruda de una reserva (salta el dominio). Fechas ISO-8601 con zona, p. ej. 2026-11-02T10:00:00Z. */
    public static UUID insertReserva(Connection c, UUID tenant, UUID pista, String inicio, String fin, String estado)
            throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement(
                "insert into reserva (id, tenant_id, pista_id, inicio, fin, estado) values (?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, id);
            ps.setObject(2, tenant);
            ps.setObject(3, pista);
            ps.setObject(4, OffsetDateTime.parse(inicio));
            ps.setObject(5, OffsetDateTime.parse(fin));
            ps.setString(6, estado);
            ps.executeUpdate();
        }
        return id;
    }

    public static long count(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
