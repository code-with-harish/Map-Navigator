-- ============================================================
-- Map Navigator - PostgreSQL setup
-- Creates and seeds the road network plus user data tables.
--
--   createdb mapnavigator
--   psql -d mapnavigator -f database/PostgresDBSetup.sql
--
-- Then set db.enabled=true in backend application.properties.
-- The seed data mirrors the embedded Bengaluru network exactly.
-- ============================================================

DROP TABLE IF EXISTS route_history;
DROP TABLE IF EXISTS saved_places;
DROP TABLE IF EXISTS edges;
DROP TABLE IF EXISTS nodes;

-- Intersections / landmarks
CREATE TABLE nodes (
    id   INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    lat  DOUBLE PRECISION NOT NULL,
    lon  DOUBLE PRECISION NOT NULL
);

-- Directed road segments (each physical road is stored in both directions)
CREATE TABLE edges (
    id              SERIAL PRIMARY KEY,
    from_node       INTEGER NOT NULL REFERENCES nodes(id),
    to_node         INTEGER NOT NULL REFERENCES nodes(id),
    road_name       TEXT NOT NULL,
    distance_km     DOUBLE PRECISION NOT NULL CHECK (distance_km > 0),
    speed_limit_kmh DOUBLE PRECISION NOT NULL CHECK (speed_limit_kmh > 0),
    UNIQUE (from_node, to_node)
);
CREATE INDEX idx_edges_from ON edges(from_node);

-- Per-user saved places (schema used by UserController when persisted)
CREATE TABLE saved_places (
    id       BIGSERIAL PRIMARY KEY,
    username TEXT NOT NULL,
    label    TEXT NOT NULL,
    node_id  INTEGER NOT NULL REFERENCES nodes(id),
    UNIQUE (username, label)
);

-- Per-user recent routes
CREATE TABLE route_history (
    id           BIGSERIAL PRIMARY KEY,
    username     TEXT NOT NULL,
    from_node    INTEGER NOT NULL REFERENCES nodes(id),
    to_node      INTEGER NOT NULL REFERENCES nodes(id),
    eta_minutes  DOUBLE PRECISION,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_history_user ON route_history(username, requested_at DESC);

-- ---------------- Seed: nodes ----------------
INSERT INTO nodes (id, name, lat, lon) VALUES
    (1, 'MG Road', 12.9757, 77.6060),
    (2, 'Trinity Circle', 12.9730, 77.6190),
    (3, 'Cubbon Park', 12.9763, 77.5929),
    (4, 'Vidhana Soudha', 12.9794, 77.5907),
    (5, 'Majestic', 12.9767, 77.5713),
    (6, 'KR Market', 12.9622, 77.5750),
    (7, 'Lalbagh', 12.9507, 77.5848),
    (8, 'Jayanagar', 12.9308, 77.5838),
    (9, 'BTM Layout', 12.9166, 77.6101),
    (10, 'Koramangala', 12.9352, 77.6245),
    (11, 'Domlur', 12.9610, 77.6387),
    (12, 'Indiranagar', 12.9719, 77.6412),
    (13, 'Ulsoor', 12.9816, 77.6285),
    (14, 'Shivajinagar', 12.9857, 77.6057),
    (15, 'Cantonment', 12.9932, 77.5982),
    (16, 'Mekhri Circle', 13.0068, 77.5813),
    (17, 'Malleshwaram', 13.0031, 77.5643),
    (18, 'Yeshwanthpur', 13.0230, 77.5520),
    (19, 'Hebbal', 13.0358, 77.5970),
    (20, 'Marathahalli', 12.9569, 77.7011),
    (21, 'HSR Layout', 12.9116, 77.6446),
    (22, 'Silk Board', 12.9177, 77.6233),
    (23, 'Richmond Circle', 12.9634, 77.5988),
    (24, 'Banashankari', 12.9250, 77.5468);

-- ---------------- Seed: roads (inserted in both directions) ----------------
INSERT INTO edges (from_node, to_node, road_name, distance_km, speed_limit_kmh) VALUES
    (1, 2, 'MG Road', 1.5, 50),
    (2, 1, 'MG Road', 1.5, 50),
    (1, 3, 'Kasturba Road', 1.5, 40),
    (3, 1, 'Kasturba Road', 1.5, 40),
    (1, 13, 'Kamaraj Road', 1.5, 40),
    (13, 1, 'Kamaraj Road', 1.5, 40),
    (1, 23, 'Residency Road', 1.6, 40),
    (23, 1, 'Residency Road', 1.6, 40),
    (2, 11, 'Old Airport Road', 2.2, 60),
    (11, 2, 'Old Airport Road', 2.2, 60),
    (2, 13, 'Ulsoor Road', 1.2, 50),
    (13, 2, 'Ulsoor Road', 1.2, 50),
    (3, 4, 'Ambedkar Veedhi', 0.6, 40),
    (4, 3, 'Ambedkar Veedhi', 0.6, 40),
    (3, 23, 'Nrupathunga Road', 1.6, 40),
    (23, 3, 'Nrupathunga Road', 1.6, 40),
    (4, 5, 'KG Road', 2.2, 40),
    (5, 4, 'KG Road', 2.2, 40),
    (4, 14, 'Cubbon Road', 1.0, 40),
    (14, 4, 'Cubbon Road', 1.0, 40),
    (5, 6, 'SJP Road', 1.7, 30),
    (6, 5, 'SJP Road', 1.7, 30),
    (5, 15, 'Seshadri Road', 3.0, 40),
    (15, 5, 'Seshadri Road', 3.0, 40),
    (5, 17, 'Platform Road', 3.5, 40),
    (17, 5, 'Platform Road', 3.5, 40),
    (6, 7, 'RV Road', 1.7, 30),
    (7, 6, 'RV Road', 1.7, 30),
    (6, 23, 'JC Road', 2.0, 30),
    (23, 6, 'JC Road', 2.0, 30),
    (6, 24, 'Mysore Road Link', 6.0, 40),
    (24, 6, 'Mysore Road Link', 6.0, 40),
    (7, 8, 'South End Road', 2.4, 40),
    (8, 7, 'South End Road', 2.4, 40),
    (7, 23, 'Richmond Road', 1.8, 40),
    (23, 7, 'Richmond Road', 1.8, 40),
    (8, 9, 'Outer Ring Road South', 3.0, 40),
    (9, 8, 'Outer Ring Road South', 3.0, 40),
    (8, 24, 'Kanakapura Cross', 4.2, 50),
    (24, 8, 'Kanakapura Cross', 4.2, 50),
    (9, 10, 'Sarjapur Link', 2.6, 40),
    (10, 9, 'Sarjapur Link', 2.6, 40),
    (9, 22, 'BTM Main Road', 1.6, 40),
    (22, 9, 'BTM Main Road', 1.6, 40),
    (10, 11, 'Inner Ring Road', 3.0, 50),
    (11, 10, 'Inner Ring Road', 3.0, 50),
    (10, 21, 'Sarjapur Road', 3.0, 40),
    (21, 10, 'Sarjapur Road', 3.0, 40),
    (10, 22, 'Hosur Road', 2.2, 40),
    (22, 10, 'Hosur Road', 2.2, 40),
    (11, 12, 'CMH Road', 1.4, 50),
    (12, 11, 'CMH Road', 1.4, 50),
    (11, 20, 'Old Airport Road East', 7.2, 60),
    (20, 11, 'Old Airport Road East', 7.2, 60),
    (12, 13, '100 Feet Road', 1.6, 50),
    (13, 12, '100 Feet Road', 1.6, 50),
    (13, 14, 'MM Road', 2.3, 40),
    (14, 13, 'MM Road', 2.3, 40),
    (14, 15, 'Bellary Road South', 1.2, 40),
    (15, 14, 'Bellary Road South', 1.2, 40),
    (15, 16, 'Bellary Road', 2.6, 50),
    (16, 15, 'Bellary Road', 2.6, 50),
    (16, 17, 'CV Raman Road', 2.2, 50),
    (17, 16, 'CV Raman Road', 2.2, 50),
    (16, 19, 'Bellary Road North', 3.5, 60),
    (19, 16, 'Bellary Road North', 3.5, 60),
    (17, 18, 'Tumkur Road Link', 3.0, 50),
    (18, 17, 'Tumkur Road Link', 3.0, 50),
    (18, 19, 'Outer Ring Road North', 5.6, 60),
    (19, 18, 'Outer Ring Road North', 5.6, 60),
    (20, 21, 'Outer Ring Road East', 6.8, 60),
    (21, 20, 'Outer Ring Road East', 6.8, 60),
    (21, 22, 'HSR 27th Main', 2.4, 40),
    (22, 21, 'HSR 27th Main', 2.4, 40);
