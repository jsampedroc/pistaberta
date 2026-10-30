package org.pistaberta.infrastructure.tenant;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

/** Puerto: de dónde sale el slug del concello. Cambiar la fuente (p.ej. claim JWT) = otro adaptador. */
public interface TenantResolver {

    Optional<String> resolveSlug(HttpServletRequest request);
}
