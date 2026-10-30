-- Semilla SOLO dev/test (perfiles dev y test añaden db/seed a flyway.locations). Nunca en prod.
-- Códigos INE verificados el 2026-10-30: A Estrada 36017, Lalín 36024.
INSERT INTO tenant (id, slug, codigo_ine, nome) VALUES
    ('00000000-0000-4000-8000-000000036017', 'a-estrada', '36017', 'A Estrada'),
    ('00000000-0000-4000-8000-000000036024', 'lalin',     '36024', 'Lalín')
ON CONFLICT (id) DO NOTHING;
