package org.pistaberta.infrastructure.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.regex.Pattern;
import org.pistaberta.domain.tenant.TenantId;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puebla el tenant de /api/** y lo limpia al terminar. 400 si falta o es inválido, 404 si el concello no existe,
 * 401 si no hay resolver configurado (prod hasta la spec de auth: falla cerrado).
 */
@Component
public class TenantFilter extends OncePerRequestFilter {

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
    private static final int MAX_SLUG_LENGTH = 64;

    private final ObjectProvider<TenantResolver> resolver;
    private final TenantCatalog catalog;
    private final ThreadLocalTenantContext context;

    public TenantFilter(ObjectProvider<TenantResolver> resolver, TenantCatalog catalog, ThreadLocalTenantContext context) {
        this.resolver = resolver;
        this.catalog = catalog;
        this.context = context;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        TenantResolver active = resolver.getIfAvailable();
        if (active == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Sin resolver de tenant configurado");
            return;
        }
        Optional<String> slug = active.resolveSlug(request);
        if (slug.isEmpty() || slug.get().length() > MAX_SLUG_LENGTH || !SLUG.matcher(slug.get()).matches()) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Tenant ausente o inválido");
            return;
        }
        Optional<TenantId> tenant = catalog.findBySlug(slug.get());
        if (tenant.isEmpty()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "Concello desconocido");
            return;
        }
        context.set(tenant.get());
        try {
            chain.doFilter(request, response);
        } finally {
            context.clear();
        }
    }
}
