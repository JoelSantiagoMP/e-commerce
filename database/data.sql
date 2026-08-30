-- =============================================================================
-- E-Commerce Micro-SaaS — Datos de prueba: Repuestos Automotrices (MySQL 8.x)
-- Requiere ejecutar schema.sql previamente.
--
-- Idempotencia: TRUNCATE al inicio garantiza estado limpio en cada ejecución
-- completa. INSERT IGNORE en clientes/órdenes/ítems evita duplicados si se
-- reinsertan registros con IDs fijos (p. ej. telegram_chat_id único).
-- =============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

TRUNCATE TABLE order_items;
TRUNCATE TABLE orders;
TRUNCATE TABLE product_variants;
TRUNCATE TABLE products;
TRUNCATE TABLE customers;
TRUNCATE TABLE categories;

SET FOREIGN_KEY_CHECKS = 1;

-- ---------------------------------------------------------------------------
-- Categorías (3)
-- ---------------------------------------------------------------------------
INSERT INTO categories (id, name, description, is_active) VALUES
    (1, 'Sistema de Frenos',        'Pastillas, discos, líquido de frenos y componentes de frenado', TRUE),
    (2, 'Filtración y Lubricantes', 'Filtros de aceite/aire/combustible y lubricantes sintéticos',  TRUE),
    (3, 'Suspensión y Dirección',   'Amortiguadores, bases, terminales y componentes de suspensión', TRUE);

-- ---------------------------------------------------------------------------
-- Productos (3)
-- ---------------------------------------------------------------------------
INSERT INTO products (id, category_id, name, description, base_price, is_active) VALUES
    (1, 1, 'Pastillas de Freno Cerámicas',
        'Pastillas cerámicas de bajo ruido y alta durabilidad. Compatible con múltiples modelos compactos y pick-ups.',
        85000.00, TRUE),
    (2, 2, 'Kit de Filtros + Aceite 20W50 Sintético',
        'Kit de mantenimiento preventivo: 4 litros de aceite sintético 20W50 + filtro de aceite OEM equivalente.',
        120000.00, TRUE),
    (3, 3, 'Amortiguadores a Gas Nitrógeno',
        'Amortiguadores a gas con tecnología de nitrógeno para mayor estabilidad y confort de marcha.',
        180000.00, TRUE);

-- ---------------------------------------------------------------------------
-- Variantes (7)
--   size  → tipo / posición (VARCHAR 20)
--   color → compatibilidad vehicular (VARCHAR 50)
-- ---------------------------------------------------------------------------
INSERT INTO product_variants (id, product_id, sku, size, color, stock, price_override, is_active) VALUES
    (1, 1, 'FRN-CHE-001', 'Juego',  'Corsa / Aveo 1.4',              15, 85000.00,  TRUE),
    (2, 1, 'FRN-REN-002', 'Juego',  'Logan / Sandero 1.6',           10, 92000.00,  TRUE),
    (3, 1, 'FRN-TOY-003', 'Juego',  'Hilux 2.4 / Fortuner',           6, 145000.00, TRUE),
    (4, 2, 'LUB-CHE-010', 'Kit 4L', '4 Litros + Filtro Aceite Corsa', 20, 120000.00, TRUE),
    (5, 2, 'LUB-REN-011', 'Kit 4L', '4 Litros + Filtro Aceite Logan', 12, 135000.00, TRUE),
    (6, 3, 'SUS-CHE-020', 'Delantero', 'Delanteros Corsa Evolution',   8, 210000.00, TRUE),
    (7, 3, 'SUS-CHE-021', 'Trasero',   'Traseros Corsa Evolution',     8, 180000.00, TRUE);

-- ---------------------------------------------------------------------------
-- Clientes (3) — INSERT IGNORE evita duplicados por telegram_chat_id único
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO customers (id, telegram_chat_id, full_name, phone, address, created_at) VALUES
    (1, 573001234567, 'Taller Mecánico El Paisa',  '+57 607 645 1122', 'Km 4 Vía Piedecuesta, Floridablanca', '2026-08-28 08:30:00'),
    (2, 573109876543, 'Autopartes Santander',      '+57 607 698 3344', 'Calle 41 #34-12, Bucaramanga',       '2026-08-29 10:15:00'),
    (3, 573205551899, 'Cliente Particular',        '+57 318 220 7788', 'Urbanización Bosques del Norte',     '2026-08-30 14:00:00');

-- ---------------------------------------------------------------------------
-- Órdenes (4) — estados variados para demo comercial
--   Orden 1: PENDING   → 2× FRN-CHE-001 + 1× LUB-CHE-010     = 290000.00
--   Orden 2: CONFIRMED → 1× FRN-TOY-003 + 1× LUB-REN-011     = 280000.00
--   Orden 3: SHIPPED   → 1× SUS-CHE-020 + 1× SUS-CHE-021     = 390000.00
--   Orden 4: CANCELLED → 2× FRN-REN-002                       = 184000.00
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO orders (id, customer_id, total_amount, status, created_at) VALUES
    (1, 1, 290000.00, 'PENDING',   '2026-08-30 09:45:00'),
    (2, 2, 280000.00, 'CONFIRMED', '2026-08-29 15:20:00'),
    (3, 3, 390000.00, 'SHIPPED',   '2026-08-28 11:10:00'),
    (4, 1, 184000.00, 'CANCELLED', '2026-08-27 16:55:00');

-- ---------------------------------------------------------------------------
-- Ítems de orden — vinculados a product_variants (ids 1-7)
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO order_items (id, order_id, product_variant_id, quantity, unit_price) VALUES
    -- Orden 1 (PENDING): 170000 + 120000 = 290000
    (1, 1, 1, 2, 85000.00),   -- FRN-CHE-001
    (2, 1, 4, 1, 120000.00),  -- LUB-CHE-010
    -- Orden 2 (CONFIRMED): 145000 + 135000 = 280000
    (3, 2, 3, 1, 145000.00),  -- FRN-TOY-003
    (4, 2, 5, 1, 135000.00),  -- LUB-REN-011
    -- Orden 3 (SHIPPED): 210000 + 180000 = 390000
    (5, 3, 6, 1, 210000.00),  -- SUS-CHE-020
    (6, 3, 7, 1, 180000.00),  -- SUS-CHE-021
    -- Orden 4 (CANCELLED): 184000
    (7, 4, 2, 2, 92000.00);   -- FRN-REN-002
