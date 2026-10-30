package org.pistaberta.infrastructure.tenant;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Header falsificable sin auth: solo dev/test. Prod no tiene resolver hasta la spec de auth (D-005). */
@Component
@Profile({"dev", "test"})
public class HeaderTenantResolver implements TenantResolver {

    public static final String HEADER = "X-Tenant-Id";

    @Override
    public Optional<String> resolveSlug(HttpServletRequest request) {
        return Optional.ofNullable(request.getHeader(HEADER)).map(String::trim).filter(s -> !s.isEmpty());
    }
}
