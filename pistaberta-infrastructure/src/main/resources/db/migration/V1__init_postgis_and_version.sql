CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE app_version (
    component  TEXT        PRIMARY KEY,
    version    TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO app_version (component, version) VALUES ('schema', '0.1.0');
