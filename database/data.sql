-- =============================================================================
-- E-Commerce Micro-SaaS — Datos de prueba (MySQL 8.x)
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
-- Categorías (2)
-- ---------------------------------------------------------------------------
INSERT INTO categories (name, description, is_active) VALUES
    ('Ropa',        'Prendas de vestir para hombre y mujer', TRUE),
    ('Accesorios',  'Complementos y artículos de moda',      TRUE);

-- ---------------------------------------------------------------------------
-- Productos (3) con variantes
-- ---------------------------------------------------------------------------
INSERT INTO products (category_id, name, description, base_price, is_active) VALUES
    (1, 'Camiseta Básica',     'Camiseta de algodón 100% unisex',           29.99, TRUE),
    (1, 'Jeans Slim Fit',      'Pantalón denim corte slim fit',             59.99, TRUE),
    (2, 'Gorra Deportiva',     'Gorra ajustable con visera curva',          19.99, TRUE);

-- Camiseta Básica — 3 variantes (talla + color)
INSERT INTO product_variants (product_id, sku, size, color, stock, price_override, is_active) VALUES
    (1, 'CAM-BAS-S-BLK',  'S',  'Negro',  50, NULL,  TRUE),
    (1, 'CAM-BAS-M-WHT',  'M',  'Blanco', 35, NULL,  TRUE),
    (1, 'CAM-BAS-L-GRY',  'L',  'Gris',   20, 27.99, TRUE);

-- Jeans Slim Fit — 2 variantes
INSERT INTO product_variants (product_id, sku, size, color, stock, price_override, is_active) VALUES
    (2, 'JNS-SLM-32-BLU', '32', 'Azul', 15, NULL, TRUE),
    (2, 'JNS-SLM-34-BLU', '34', 'Azul', 10, NULL, TRUE);

-- Gorra Deportiva — 2 variantes (color)
INSERT INTO product_variants (product_id, sku, size, color, stock, price_override, is_active) VALUES
    (3, 'GOR-DEP-UNI-RED',  'UNI', 'Rojo',  25, NULL, TRUE),
    (3, 'GOR-DEP-UNI-BLK',  'UNI', 'Negro', 30, NULL, TRUE);

-- ---------------------------------------------------------------------------
-- Clientes (3) — INSERT IGNORE evita duplicados por telegram_chat_id único
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO customers (id, telegram_chat_id, full_name, phone, address, created_at) VALUES
    (1, 5847291036, 'María González',      '+57 300 123 4567', 'Calle 45 #12-34, Bogotá',           '2026-08-28 10:15:00'),
    (2, 5918374620, 'Carlos Rodríguez',    '+57 310 987 6543', 'Carrera 7 #80-20, Medellín',        '2026-08-29 14:30:00'),
    (3, 6029481753, 'Ana Lucía Martínez',  '+57 320 555 8899', 'Av. Santander #25-10, Bucaramanga', '2026-08-30 09:00:00');

-- ---------------------------------------------------------------------------
-- Órdenes (4) — estados variados para demo del panel admin
--   Orden 1: PENDING   → 2× Camiseta M Blanco + 1× Gorra Roja  = 79.97
--   Orden 2: CONFIRMED → 1× Jeans 32 + 1× Camiseta L Gris      = 87.98
--   Orden 3: SHIPPED   → 3× Gorra Negra                        = 59.97
--   Orden 4: CANCELLED → 2× Jeans 34                             = 119.98
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO orders (id, customer_id, total_amount, status, created_at) VALUES
    (1, 1,  79.97, 'PENDING',   '2026-08-30 11:20:00'),
    (2, 2,  87.98, 'CONFIRMED', '2026-08-29 16:45:00'),
    (3, 3,  59.97, 'SHIPPED',   '2026-08-28 18:10:00'),
    (4, 1, 119.98, 'CANCELLED', '2026-08-27 08:55:00');

-- ---------------------------------------------------------------------------
-- Ítems de orden — vinculados a product_variants (ids 1-7 del seed anterior)
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO order_items (id, order_id, product_variant_id, quantity, unit_price) VALUES
    -- Orden 1 (PENDING): total 59.98 + 19.99 = 79.97
    (1, 1, 2, 2, 29.99),   -- CAM-BAS-M-WHT
    (2, 1, 6, 1, 19.99),   -- GOR-DEP-UNI-RED
    -- Orden 2 (CONFIRMED): total 59.99 + 27.99 = 87.98
    (3, 2, 4, 1, 59.99),   -- JNS-SLM-32-BLU
    (4, 2, 3, 1, 27.99),   -- CAM-BAS-L-GRY (price_override)
    -- Orden 3 (SHIPPED): total 59.97
    (5, 3, 7, 3, 19.99),   -- GOR-DEP-UNI-BLK
    -- Orden 4 (CANCELLED): total 119.98
    (6, 4, 5, 2, 59.99);   -- JNS-SLM-34-BLU
