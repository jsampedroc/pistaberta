package org.pistaberta.domain.acceso;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Token opaco de acceso a una reserva (D-011): 256 bits en Base64URL sin relleno (43 caracteres).
 *
 * <p>Es un ticket al portador. Por eso {@link #toString()} nunca revela el valor (no puede acabar en logs por
 * accidente) y solo se persiste su {@link #hash() SHA-256}. No sobrescribe {@code equals}: comparar tokens se hace con
 * {@link #coincideCon(byte[])}, en tiempo constante.
 */
public final class TokenAcceso {

    public static final int LONGITUD = 43;
    private static final Pattern FORMATO = Pattern.compile("[A-Za-z0-9_-]{" + LONGITUD + "}");

    private final String valor;

    private TokenAcceso(String valor) {
        this.valor = valor;
    }

    /** @throws TokenAccesoInvalido si no tiene el formato de un token emitido (43 caracteres Base64URL). */
    public static TokenAcceso de(String valor) {
        Objects.requireNonNull(valor, "valor");
        if (!FORMATO.matcher(valor).matches()) {
            throw new TokenAccesoInvalido("Formato de token no válido");
        }
        return new TokenAcceso(valor);
    }

    /** El valor en claro: solo para devolverlo una vez al titular. Nunca se escribe en logs ni en BD. */
    public String valor() {
        return valor;
    }

    /** SHA-256 del valor (32 bytes): lo único que se guarda. Con 256 bits de entropía no hace falta un hash lento. */
    public byte[] hash() {
        try {
            return MessageDigest.getInstance("SHA-256").digest(valor.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 es obligatorio en toda JVM", e);
        }
    }

    /** Comparación en tiempo constante con un hash almacenado. */
    public boolean coincideCon(byte[] hashAlmacenado) {
        return MessageDigest.isEqual(hash(), hashAlmacenado);
    }

    @Override
    public String toString() {
        return "TokenAcceso[***]";
    }
}
