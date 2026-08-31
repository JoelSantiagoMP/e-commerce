# Software Architecture & Specification: E-Commerce Micro-SaaS & Telegram Bot

## 1. Project Overview
Sistema de gestión de inventario y pedidos orientado a pequeños comercios (SaaS Micro-Local).
El sistema permite a los administradores gestionar stock, productos y estados de órdenes desde un Panel Web, mientras que los clientes finales consultan el catálogo y realizan compras interactivas a través de un Bot de Telegram.

---

## 2. Tech Stack & Infrastructure
- **Backend:** Java 17, Spring Boot 3.x, Spring Data JPA, Spring Security (básico/JWT), Bean Validation.
- **Database:** MySQL 8.x (Host local para dev / Cloud en Aiven para prod).
- **Frontend Admin:** React.js (Vite) + Tailwind CSS.
- **Messaging Integration:** Telegram Bot API via HTTP Webhooks.
- **Hosting/Cloud (Free Tier):** Render (Backend), Vercel (Frontend Admin), Aiven (MySQL).

---

## 3. Engineering Guidelines & Architectural Rules
1. **Layered Architecture:** Estricta separación de capas (`Controller` -> `Service` -> `Repository`).
2. **Data Transfer Objects (DTOs):** Prohibido exponer Entidades JPA directamente en la capa Controller. Usar DTOs para Request y Response.
3. **Transactional Integrity:** Métodos de Service que modifiquen stock u órdenes DEBEN llevar la anotación `@Transactional`.
4. **Exception Handling:** Manejo global de excepciones mediante `@ControllerAdvice` retornando respuestas formateadas.
5. **Database Naming:** Tablas y columnas en `snake_case`. Clases Java en `PascalCase` y atributos en `camelCase`.
6. **Soft Delete:** Los productos y variantes no se eliminan físicamente de la BD; se marca `is_active = false`.

---

## 4. Domain Data Model (SQL DDL Schema)

Entidades clave:
1. `categories` (id, name, description, is_active)
2. `products` (id, category_id, name, description, base_price, is_active, created_at)
3. `product_variants` (id, product_id, sku, size, color, stock, price_override, is_active)
4. `customers` (id, telegram_chat_id, full_name, phone, address, created_at)
5. `orders` (id, customer_id, total_amount, status, created_at) -> status: PENDING, CONFIRMED, SHIPPED, CANCELLED
6. `order_items` (id, order_id, product_variant_id, quantity, unit_price)

---

## 5. Execution Roadmap (Phases)

### Phase 1: Database Schema & Spring Boot Core Domain
- [ ] Configuración del pom.xml y estructura de paquetes Spring Boot.
- [ ] Definición del DDL SQL e inicialización de la BD en MySQL.
- [ ] Mapeo de Entidades JPA (`Category`, `Product`, `ProductVariant`, `Customer`, `Order`, `OrderItem`).
- [ ] Creación de Repositorios (`JpaRepository`).

### Phase 2: REST API & Business Logic (Admin Management)
- [ ] Mapeo de DTOs y Beans de Validación.
- [ ] Service & Controller para CRUD de Categorías, Productos y Variantes.
- [ ] Service & Controller para Gestión de Órdenes y control transaccional de Stock.
- [ ] Manejador Global de Excepciones (`@ControllerAdvice`).

### Phase 3: Telegram Bot Webhook Integration
- [x] Endpoint seguro `/api/v1/telegram/webhook` con verificación de secret token.
- [x] Service de Telegram para parseo de comandos (`/start`, `/help`, `/catalogo`, `/comprar`).
- [x] Integración del flujo de creación de orden desde Telegram hacia el Service de Órdenes.
- [x] Integración con Google Gemini (`gemini-2.5-flash`) y function calling (`listarCatalogo`, `consultarStock`, `crearOrden`, `prepararPedido`, `confirmarPedido`).
- [x] Sesión conversacional por chat (`ChatSessionService`) para recordar productos mostrados.
- [x] Detección local de intención de compra (`PurchaseIntentResolver`) sin exigir SKU al cliente.
- [x] Flujo de confirmación multi-producto con teclado inline (✅ Confirmar / ❌ Cancelar).
- [x] Deduplicación de updates de Telegram.

### Phase 4: Frontend Admin Dashboard (React + Vite)
- [ ] Configuración inicial de React + Vite + Tailwind CSS.
- [ ] Pantalla de Gestión de Inventario (Tabla de Productos, Modal de Creación/Edición).
- [ ] Pantalla de Gestión de Pedidos (Cambio de estado: Pendiente -> Enviado).

### Phase 5: Deployment & Documentation
- [ ] Despliegue de MySQL en Aiven y Backend en Render.
- [ ] Registro del Webhook de producción en Telegram.
- [ ] Despliegue del Frontend en Vercel.
- [ ] Redacción de README.md principal con diagramas E-R y demo en vivo.