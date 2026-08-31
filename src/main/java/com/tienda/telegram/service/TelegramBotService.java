package com.tienda.telegram.service;

import com.tienda.config.MetricsConfig;
import com.tienda.dto.CategoryDTO;
import com.tienda.dto.OrderDTO;
import com.tienda.dto.OrderItemRequestDTO;
import com.tienda.dto.ProductDTO;
import com.tienda.dto.ProductVariantDTO;
import com.tienda.entity.Customer;
import com.tienda.entity.Product;
import com.tienda.entity.ProductVariant;
import com.tienda.exception.InsufficientStockException;
import com.tienda.exception.ResourceNotFoundException;
import com.tienda.repository.CustomerRepository;
import com.tienda.repository.ProductVariantRepository;
import com.tienda.service.OrderService;
import com.tienda.service.ProductService;
import com.tienda.telegram.dto.TelegramCallbackQueryDTO;
import com.tienda.telegram.dto.TelegramChatDTO;
import com.tienda.telegram.dto.TelegramMessageDTO;
import com.tienda.telegram.dto.TelegramUpdateDTO;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramBotService {

    private static final String CONFIRM_ORDER_PREFIX = "CONFIRM_ORDER:";
    private static final String SELECT_QTY_PREFIX = "SELECT_QTY:";
    private static final String CANCEL_ORDER_CALLBACK = "CANCEL_ORDER";

    private final ProductService productService;
    private final OrderService orderService;
    private final CustomerRepository customerRepository;
    private final ProductVariantRepository productVariantRepository;
    private final TelegramClientService telegramClientService;
    private final MeterRegistry meterRegistry;

    @Value("${telegram.bot.username:Autorepuestosdemo_bot}")
    private String botUsername;

    public String processUpdate(TelegramUpdateDTO update) {
        if (update == null) {
            log.warn("Update nulo recibido");
            return "Update nulo.";
        }

        if (update.getCallbackQuery() != null) {
            return handleCallbackQuery(update.getCallbackQuery());
        }

        if (update.getMessage() == null) {
            log.warn("Update recibido sin mensaje válido. updateId={}", update.getUpdateId());
            return "Mensaje no procesable.";
        }

        TelegramMessageDTO message = update.getMessage();
        TelegramChatDTO chat = message.getChat();

        if (chat == null || chat.getId() == null) {
            log.warn("Mensaje sin chat válido. messageId={}", message.getMessageId());
            return "Chat no identificado.";
        }

        Long chatId = chat.getId();
        String text = message.getText();

        if (text == null || text.isBlank()) {
            log.info("Mensaje sin texto recibido de chatId={}", chatId);
            return sendAndReturn(chatId, buildMenuMessage());
        }

        String trimmedText = text.trim();
        String command = trimmedText.split("\\s+")[0].toLowerCase();
        log.info("Procesando comando '{}' de chatId={}", command, chatId);
        recordTelegramCommand(command);

        boolean[] messageAlreadySent = {false};
        String response;
        try {
            response = switch (command) {
                case "/start" -> handleStart(chatId, chat);
                case "/catalogo" -> handleCatalogo();
                case "/comprar" -> handleComprar(chatId, chat, trimmedText, messageAlreadySent);
                default -> trimmedText.startsWith("/")
                        ? "Comando no reconocido.\n\n" + buildMenuMessage()
                        : buildMenuMessage();
            };
        } catch (ResourceNotFoundException | InsufficientStockException ex) {
            log.warn("Error de negocio en Telegram chatId={}: {}", chatId, ex.getMessage());
            response = "⚠️ " + ex.getMessage();
        } catch (IllegalArgumentException ex) {
            log.warn("Argumento inválido en Telegram chatId={}: {}", chatId, ex.getMessage());
            response = ex.getMessage();
        }

        if (messageAlreadySent[0]) {
            return response;
        }

        return sendAndReturn(chatId, response);
    }

    private String handleCallbackQuery(TelegramCallbackQueryDTO callback) {
        if (callback.getMessage() == null || callback.getMessage().getChat() == null) {
            log.warn("Callback query sin mensaje o chat válido. callbackId={}", callback.getId());
            return "Callback no procesable.";
        }

        Long chatId = callback.getMessage().getChat().getId();
        Long messageId = callback.getMessage().getMessageId();
        String data = callback.getData();

        telegramClientService.answerCallbackQuery(callback.getId());

        try {
            if (data != null && data.startsWith(SELECT_QTY_PREFIX)) {
                String payload = data.substring(SELECT_QTY_PREFIX.length()).trim();
                String[] selectParts = payload.split(":", 2);
                String sku = selectParts[0].trim().toUpperCase();
                int quantity = parsePositiveQuantity(selectParts[1]);
                return handleSelectQuantity(chatId, messageId, sku, quantity);
            }

            if (data != null && data.startsWith(CONFIRM_ORDER_PREFIX)) {
                String payload = data.substring(CONFIRM_ORDER_PREFIX.length()).trim();
                String[] confirmParts = payload.split(":", 2);
                String sku = confirmParts[0].trim().toUpperCase();
                int quantity = confirmParts.length > 1
                        ? parsePositiveQuantity(confirmParts[1])
                        : 1;
                return handleConfirmOrder(chatId, messageId, callback.getMessage().getChat(), sku, quantity);
            }

            if (CANCEL_ORDER_CALLBACK.equals(data)) {
                String cancelMessage = "❌ *Pedido cancelado.*\n\nPuedes volver a intentarlo con /catalogo.";
                telegramClientService.editMessageText(chatId, messageId, cancelMessage);
                return cancelMessage;
            }

            log.warn("Callback data no reconocido: {}", data);
            return "Callback no reconocido.";
        } catch (ResourceNotFoundException | InsufficientStockException | IllegalArgumentException ex) {
            log.warn("Error de negocio en callback chatId={}: {}", chatId, ex.getMessage());
            String errorMessage = ex instanceof IllegalArgumentException
                    ? ex.getMessage()
                    : "⚠️ " + ex.getMessage();
            telegramClientService.editMessageText(chatId, messageId, errorMessage);
            return errorMessage;
        }
    }

    private String handleSelectQuantity(Long chatId, Long messageId, String sku, int quantity) {
        ProductVariant variant = productVariantRepository.findBySku(sku)
                .filter(v -> Boolean.TRUE.equals(v.getIsActive()))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Repuesto no encontrado o inactivo con SKU: " + sku));

        if (quantity > variant.getStock()) {
            String insufficientStockMessage = buildInsufficientStockMessage(sku, quantity, variant.getStock());
            telegramClientService.editMessageText(chatId, messageId, insufficientStockMessage);
            return insufficientStockMessage;
        }

        Product product = variant.getProduct();
        String productName = product != null ? product.getName() : sku;
        BigDecimal unitPrice = resolveVariantPrice(variant, product);
        BigDecimal total = unitPrice.multiply(BigDecimal.valueOf(quantity));

        String summaryMessage = buildOrderSummaryMessage(productName, sku, quantity, unitPrice, total);
        Map<String, Object> keyboard = telegramClientService.buildOrderConfirmationKeyboard(sku, quantity);

        telegramClientService.editMessageTextWithInlineKeyboard(chatId, messageId, summaryMessage, keyboard);

        log.info("Cantidad seleccionada vía callback Telegram. sku={}, quantity={}, chatId={}", sku, quantity, chatId);
        return summaryMessage;
    }

    private String handleConfirmOrder(
            Long chatId, Long messageId, TelegramChatDTO chat, String sku, int quantity) {
        Customer customer = resolveOrCreateCustomer(chatId, chat);

        ProductVariant variant = productVariantRepository.findBySku(sku)
                .filter(v -> Boolean.TRUE.equals(v.getIsActive()))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Repuesto no encontrado o inactivo con SKU: " + sku));

        if (variant.getStock() < quantity) {
            throw new InsufficientStockException(
                    "Stock insuficiente para SKU: " + sku + ". Disponible: " + variant.getStock());
        }

        OrderDTO order = orderService.createOrder(
                customer.getId(),
                List.of(OrderItemRequestDTO.builder()
                        .productVariantId(variant.getId())
                        .quantity(quantity)
                        .build()));

        log.info("Orden confirmada vía callback Telegram: orderId={}, sku={}, quantity={}, chatId={}",
                order.getId(), sku, quantity, chatId);

        String successMessage = buildOrderSuccessMessage(order);
        telegramClientService.editMessageText(chatId, messageId, successMessage);
        return successMessage;
    }

    private String sendAndReturn(Long chatId, String response) {
        telegramClientService.sendMessage(chatId, response);
        return response;
    }

    private String handleStart(Long chatId, TelegramChatDTO chat) {
        Customer customer = resolveOrCreateCustomer(chatId, chat);

        log.info("Comando /start procesado para chatId={}, customerId={}", chatId, customer.getId());

        return "¡Bienvenido a Autorepuestos Demo, " + customer.getFullName() + "! 🔧\n\n"
                + "Somos tu tienda de repuestos automotrices.\n"
                + "Bot: @" + botUsername + "\n\n"
                + buildMenuMessage();
    }

    private String buildMenuMessage() {
        return """
                📋 *Menú de Autorepuestos*
                
                /catalogo — Ver pastillas, filtros, amortiguadores y más
                /comprar SKU [CANTIDAD] — Simular pedido (ej: /comprar FRN-CHE-001 o /comprar FRN-CHE-001 3)
                /start — Ver este menú
                
                Repuestos disponibles: frenos, lubricantes y suspensión.""";
    }

    private String handleCatalogo() {
        List<CategoryDTO> categories = productService.getAllActiveCategories();

        if (categories.isEmpty()) {
            log.info("Catálogo consultado: sin categorías activas");
            return "No hay repuestos disponibles en este momento.";
        }

        StringBuilder catalog = new StringBuilder("🔧 *Catálogo de Autorepuestos*\n\n");

        for (CategoryDTO category : categories) {
            List<ProductDTO> products = productService.getActiveProductsByCategory(category.getId());

            if (products.isEmpty()) {
                continue;
            }

            catalog.append("▸ ").append(category.getName()).append("\n");

            for (ProductDTO product : products) {
                catalog.append("  • ").append(product.getName()).append("\n");

                List<ProductVariantDTO> variants = productService.getActiveVariantsByProductId(product.getId());

                for (ProductVariantDTO variant : variants) {
                    BigDecimal price = variant.getPriceOverride() != null
                            ? variant.getPriceOverride()
                            : product.getBasePrice();

                    catalog.append("    - SKU: ").append(variant.getSku())
                            .append(" | $").append(price)
                            .append(" | Stock: ").append(variant.getStock())
                            .append("\n");
                }

                catalog.append("\n");
            }
        }

        catalog.append("Para pedir: /comprar SKU (selector) o /comprar SKU [CANTIDAD]");

        log.info("Catálogo generado con {} categorías activas", categories.size());
        return catalog.toString().trim();
    }

    private String handleComprar(Long chatId, TelegramChatDTO chat, String fullText, boolean[] messageAlreadySent) {
        String[] parts = fullText.split("\\s+");

        if (parts.length < 2 || parts[1].isBlank()) {
            return "Indica el SKU del repuesto.\nEjemplo: /comprar FRN-CHE-001";
        }

        String sku = parts[1].trim().toUpperCase();
        resolveOrCreateCustomer(chatId, chat);

        ProductVariant variant = productVariantRepository.findBySku(sku)
                .filter(v -> Boolean.TRUE.equals(v.getIsActive()))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Repuesto no encontrado o inactivo con SKU: " + sku));

        if (variant.getStock() < 1) {
            throw new InsufficientStockException("Sin stock disponible para SKU: " + sku);
        }

        Product product = variant.getProduct();
        String productName = product != null ? product.getName() : sku;

        if (!hasQuantitySpecified(parts)) {
            String selectionMessage = buildQuantitySelectionMessage(productName, sku, variant.getStock());
            Map<String, Object> keyboard = telegramClientService.buildQuantitySelectorKeyboard(sku);

            telegramClientService.sendMessageWithInlineKeyboard(chatId, selectionMessage, keyboard);
            messageAlreadySent[0] = true;

            log.info("Selector de cantidad enviado. sku={}, chatId={}", sku, chatId);
            return selectionMessage;
        }

        int quantity = parsePositiveQuantity(parts[2]);
        return presentOrderSummary(chatId, sku, variant, productName, quantity, messageAlreadySent);
    }

    private boolean hasQuantitySpecified(String[] parts) {
        return parts.length >= 3 && !parts[2].isBlank();
    }

    private String presentOrderSummary(
            Long chatId,
            String sku,
            ProductVariant variant,
            String productName,
            int quantity,
            boolean[] messageAlreadySent) {

        if (quantity > variant.getStock()) {
            return buildInsufficientStockMessage(sku, quantity, variant.getStock());
        }

        Product product = variant.getProduct();
        BigDecimal unitPrice = resolveVariantPrice(variant, product);
        BigDecimal total = unitPrice.multiply(BigDecimal.valueOf(quantity));

        String summaryMessage = buildOrderSummaryMessage(productName, sku, quantity, unitPrice, total);
        Map<String, Object> keyboard = telegramClientService.buildOrderConfirmationKeyboard(sku, quantity);

        telegramClientService.sendMessageWithInlineKeyboard(chatId, summaryMessage, keyboard);
        messageAlreadySent[0] = true;

        log.info("Resumen de pedido enviado con confirmación interactiva. sku={}, quantity={}, chatId={}",
                sku, quantity, chatId);
        return summaryMessage;
    }

    private String buildQuantitySelectionMessage(String productName, String sku, int availableStock) {
        return """
                🛒 *Selecciona la cantidad*
                • *Producto:* %s
                • *SKU:* %s
                • *Stock disponible:* %d
                
                Elige una opción rápida o usa /comprar %s [CANTIDAD]"""
                .formatted(productName, sku, availableStock, sku);
    }

    private int parsePositiveQuantity(String rawQuantity) {
        try {
            int quantity = Integer.parseInt(rawQuantity.trim());
            if (quantity <= 0) {
                throw new IllegalArgumentException(
                        "La cantidad debe ser un entero positivo.\nEjemplo: /comprar FRN-CHE-001 3");
            }
            return quantity;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(
                    "Cantidad inválida. Usa un número entero positivo.\nEjemplo: /comprar FRN-CHE-001 3");
        }
    }

    private String buildOrderSummaryMessage(
            String productName, String sku, int quantity, BigDecimal unitPrice, BigDecimal total) {
        return """
                🛒 *Resumen de tu Pedido*
                • *Producto:* %s
                • *SKU:* %s
                • *Cantidad:* %d
                • *Precio unitario:* %s
                • *Total:* %s
                
                ¿Deseas confirmar este pedido?"""
                .formatted(productName, sku, quantity, formatCop(unitPrice), formatCop(total));
    }

    private String buildInsufficientStockMessage(String sku, int requestedQuantity, int availableStock) {
        return """
                ⚠️ *Stock insuficiente*
                Solicitaste *%d* unidades de *%s*, pero solo hay *%d* disponibles.
                
                Puedes intentar: /comprar %s %d"""
                .formatted(requestedQuantity, sku, availableStock, sku, availableStock);
    }

    private String buildOrderSuccessMessage(OrderDTO order) {
        return """
                ✅ *¡Pedido Registrado con Éxito!*
                *Orden #* %d — Estado: *%s*
                *Total:* %s
                
                💳 *Instrucciones de Pago:*
                Por favor realiza la transferencia Nequi / Bancolombia a la cuenta `3001234567` o utiliza nuestro link de pago directo:
                https://pago.autorepuestos.com/order-%d
                
                Un asesor validará tu pago para proceder con el despacho."""
                .formatted(
                        order.getId(),
                        order.getStatus(),
                        formatCop(order.getTotalAmount()),
                        order.getId());
    }

    private BigDecimal resolveVariantPrice(ProductVariant variant, Product product) {
        if (variant.getPriceOverride() != null) {
            return variant.getPriceOverride();
        }
        if (product != null && product.getBasePrice() != null) {
            return product.getBasePrice();
        }
        return BigDecimal.ZERO;
    }

    private String formatCop(BigDecimal amount) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(new Locale("es", "CO"));
        symbols.setGroupingSeparator('.');
        DecimalFormat formatter = new DecimalFormat("$#,##0", symbols);
        return formatter.format(amount);
    }

    private Customer resolveOrCreateCustomer(Long chatId, TelegramChatDTO chat) {
        return customerRepository.findByTelegramChatId(chatId)
                .orElseGet(() -> {
                    String fullName = chat.getFirstName() != null ? chat.getFirstName() : "Cliente Telegram";

                    Customer newCustomer = Customer.builder()
                            .telegramChatId(chatId)
                            .fullName(fullName)
                            .createdAt(LocalDateTime.now())
                            .build();

                    Customer saved = customerRepository.save(newCustomer);
                    log.info("Nuevo cliente registrado vía Telegram: chatId={}, nombre={}", chatId, fullName);
                    return saved;
                });
    }

    private void recordTelegramCommand(String command) {
        String commandTag = switch (command) {
            case "/start" -> "start";
            case "/catalogo" -> "catalogo";
            case "/comprar" -> "comprar";
            default -> "unknown";
        };

        meterRegistry.counter(MetricsConfig.TELEGRAM_COMMANDS_METRIC, "command", commandTag).increment();
    }
}
