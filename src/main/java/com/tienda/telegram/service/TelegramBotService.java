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
import com.tienda.gemini.dto.GeminiChatResult;
import com.tienda.gemini.service.GeminiService;
import com.tienda.exception.InsufficientStockException;
import com.tienda.exception.ResourceNotFoundException;
import com.tienda.repository.CustomerRepository;
import com.tienda.repository.ProductVariantRepository;
import com.tienda.service.OrderService;
import com.tienda.service.ProductService;
import com.tienda.telegram.dto.PendingOrderLine;
import com.tienda.telegram.dto.TelegramCallbackQueryDTO;
import com.tienda.telegram.dto.TelegramChatDTO;
import com.tienda.telegram.dto.TelegramMessageDTO;
import com.tienda.telegram.dto.TelegramUpdateDTO;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramBotService {

    private static final String CONFIRM_ORDER_PREFIX = "CONFIRM_ORDER:";
    private static final String CONFIRM_MULTI_ORDER_PREFIX = "CONFIRM_MULTI_ORDER:";
    private static final String SELECT_QTY_PREFIX = "SELECT_QTY:";
    private static final String BUY_SKU_PREFIX = "BUY_SKU:";
    private static final String SHOW_CATALOG_CALLBACK = "SHOW_CATALOG";
    private static final String CANCEL_ORDER_CALLBACK = "CANCEL_ORDER";

    private static final Set<String> GREETING_EXACT = Set.of(
            "hola", "buenas", "buenos dias", "buenas tardes", "buenas noches",
            "inicio", "hey", "saludos", "buen dia");

    private static final Set<String> GREETING_SHORT_PREFIX = Set.of("hola", "buenas", "inicio", "hey");

    private static final List<String> CATALOG_KEYWORDS = List.of(
            "catalogo", "que vendes", "productos", "precios", "inventario", "repuestos");

    private final ProductService productService;
    private final OrderService orderService;
    private final CustomerRepository customerRepository;
    private final ProductVariantRepository productVariantRepository;
    private final TelegramClientService telegramClientService;
    private final GeminiService geminiService;
    private final ChatSessionService chatSessionService;
    private final PurchaseIntentResolver purchaseIntentResolver;
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
                case "/help" -> buildMenuMessage();
                case "/catalogo" -> sendCatalog(chatId, messageAlreadySent);
                case "/comprar" -> handleComprar(chatId, chat, trimmedText, messageAlreadySent);
                default -> trimmedText.startsWith("/")
                        ? "Comando no reconocido.\n\n" + buildMenuMessage()
                        : routeLocalIntentOrGemini(chatId, chat, trimmedText, messageAlreadySent);
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
            if (SHOW_CATALOG_CALLBACK.equals(data)) {
                return sendCatalogEdit(chatId, messageId);
            }

            if (data != null && data.startsWith(BUY_SKU_PREFIX)) {
                String sku = data.substring(BUY_SKU_PREFIX.length()).trim().toUpperCase();
                return startPurchaseFromSku(chatId, messageId, callback.getMessage().getChat(), sku, true);
            }

            if (data != null && data.startsWith(SELECT_QTY_PREFIX)) {
                String payload = data.substring(SELECT_QTY_PREFIX.length()).trim();
                String[] selectParts = payload.split(":", 2);
                String sku = selectParts[0].trim().toUpperCase();
                int quantity = parsePositiveQuantity(selectParts[1]);
                return handleSelectQuantity(chatId, messageId, sku, quantity);
            }

            if (data != null && data.startsWith(CONFIRM_MULTI_ORDER_PREFIX)) {
                String payload = data.substring(CONFIRM_MULTI_ORDER_PREFIX.length()).trim();
                return handleConfirmMultiOrder(
                        chatId, messageId, callback.getMessage().getChat(), decodeMultiOrderPayload(payload));
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
                chatSessionService.clearPendingOrder(chatId);
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
                /help — Ver este menú
                /start — Bienvenida
                
                También puedes escribirme en lenguaje natural: precios, stock o pedidos.""";
    }

    private String sendCatalog(Long chatId, boolean[] messageAlreadySent) {
        CatalogContent catalog = buildCatalogContent();

        if (catalog.skus().isEmpty()) {
            return catalog.text();
        }

        telegramClientService.sendMessageWithInlineKeyboard(
                chatId,
                catalog.text(),
                telegramClientService.buildCatalogKeyboard(catalog.skus()));
        chatSessionService.recordShownProductsFromCatalog(chatId, catalog.skus());
        messageAlreadySent[0] = true;
        log.info("Catálogo enviado con {} SKUs y teclado inline. chatId={}", catalog.skus().size(), chatId);
        return catalog.text();
    }

    private String sendCatalogEdit(Long chatId, Long messageId) {
        CatalogContent catalog = buildCatalogContent();

        if (catalog.skus().isEmpty()) {
            telegramClientService.editMessageText(chatId, messageId, catalog.text());
            return catalog.text();
        }

        telegramClientService.editMessageTextWithInlineKeyboard(
                chatId,
                messageId,
                catalog.text(),
                telegramClientService.buildCatalogKeyboard(catalog.skus()));
        return catalog.text();
    }

    private record CatalogContent(String text, List<String> skus) {}

    private CatalogContent buildCatalogContent() {
        List<CategoryDTO> categories = productService.getAllActiveCategories();

        if (categories.isEmpty()) {
            log.info("Catálogo consultado: sin categorías activas");
            return new CatalogContent("📦 No hay repuestos disponibles en este momento.", List.of());
        }

        StringBuilder catalog = new StringBuilder("""
                🛠️ *Catálogo de Autorepuestos Demo*
                Repuestos con stock disponible. Toca 🛒 para comprar o usa /comprar SKU.

                """);
        List<String> skus = new ArrayList<>();
        boolean anyItemListed = false;

        for (CategoryDTO category : categories) {
            List<ProductDTO> products = productService.getActiveProductsByCategory(category.getId());
            if (products.isEmpty()) {
                continue;
            }

            StringBuilder categorySection = new StringBuilder();
            categorySection.append("📂 *").append(category.getName()).append("*\n\n");

            for (ProductDTO product : products) {
                List<ProductVariantDTO> variants = productService.getActiveVariantsByProductId(product.getId());

                for (ProductVariantDTO variant : variants) {
                    if (variant.getStock() == null || variant.getStock() <= 0) {
                        continue;
                    }

                    BigDecimal price = variant.getPriceOverride() != null
                            ? variant.getPriceOverride()
                            : product.getBasePrice();

                    categorySection.append(formatVariantEntry(product.getName(), variant, price)).append("\n");
                    skus.add(variant.getSku());
                    anyItemListed = true;
                }
            }

            if (categorySection.length() > ("📂 *" + category.getName() + "*\n\n").length()) {
                catalog.append(categorySection).append("\n");
            }
        }

        if (!anyItemListed) {
            return new CatalogContent("📦 No hay repuestos con stock disponible en este momento.", List.of());
        }

        catalog.append("""
                💬 *¿Cómo pedir?*
                • Toca un botón 🛒 debajo
                • O escribe: /comprar SKU
                • También puedo asesorarte en lenguaje natural 🚗""");

        log.info("Catálogo generado con {} categorías y {} SKUs", categories.size(), skus.size());
        return new CatalogContent(catalog.toString().trim(), List.copyOf(skus));
    }

    private String formatVariantEntry(String productName, ProductVariantDTO variant, BigDecimal price) {
        return """
                🚗 *%s*
                • Aplicación: %s
                • SKU: `%s`
                • Precio: %s COP
                • Disponibilidad: %d unidades"""
                .formatted(
                        productName,
                        resolveApplication(variant),
                        variant.getSku(),
                        formatCop(price),
                        variant.getStock());
    }

    private String resolveApplication(ProductVariantDTO variant) {
        if (variant.getColor() != null && !variant.getColor().isBlank()) {
            return variant.getColor();
        }
        if (variant.getSize() != null && !variant.getSize().isBlank()) {
            return variant.getSize();
        }
        return "Consultar compatibilidad";
    }

    private String resolveApplication(ProductVariant variant) {
        if (variant.getColor() != null && !variant.getColor().isBlank()) {
            return variant.getColor();
        }
        if (variant.getSize() != null && !variant.getSize().isBlank()) {
            return variant.getSize();
        }
        return "Consultar compatibilidad";
    }

    private String routeLocalIntentOrGemini(
            Long chatId, TelegramChatDTO chat, String text, boolean[] messageAlreadySent) {
        String normalized = normalizeForIntent(text);
        LocalIntent intent = resolveLocalIntent(normalized);

        return switch (intent) {
            case CATALOG -> {
                log.info("Intención local CATALOG detectada para chatId={}: '{}'", chatId, text);
                yield sendCatalog(chatId, messageAlreadySent);
            }
            case GREETING -> {
                log.info("Intención local GREETING detectada para chatId={}: '{}'", chatId, text);
                yield handleGreeting(chatId, chat);
            }
            case NONE -> handleFreeText(chatId, chat, text, messageAlreadySent);
        };
    }

    enum LocalIntent {
        CATALOG, GREETING, NONE
    }

    String normalizeForIntent(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.trim().toLowerCase(Locale.ROOT);
        normalized = Normalizer.normalize(normalized, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        normalized = normalized.replaceAll("[^a-z0-9\\s]", " ");
        return normalized.replaceAll("\\s+", " ").trim();
    }

    LocalIntent resolveLocalIntent(String normalizedText) {
        if (normalizedText.isBlank()) {
            return LocalIntent.NONE;
        }

        if (GREETING_EXACT.contains(normalizedText) || isShortGreeting(normalizedText)) {
            return LocalIntent.GREETING;
        }

        for (String keyword : CATALOG_KEYWORDS) {
            if (normalizedText.contains(keyword)) {
                return LocalIntent.CATALOG;
            }
        }

        return LocalIntent.NONE;
    }

    private boolean isShortGreeting(String normalizedText) {
        String[] tokens = normalizedText.split("\\s+");
        if (tokens.length > 2) {
            return false;
        }
        return GREETING_SHORT_PREFIX.contains(tokens[0]);
    }

    private String handleGreeting(Long chatId, TelegramChatDTO chat) {
        Customer customer = resolveOrCreateCustomer(chatId, chat);
        return "¡Hola, " + customer.getFullName() + "! 🚗🛠️\n\n"
                + "Soy tu Asesor Comercial Virtual de *Autorepuestos Demo*.\n\n"
                + buildMenuMessage();
    }

    private String handleFreeText(Long chatId, TelegramChatDTO chat, String text, boolean[] messageAlreadySent) {
        Customer customer = resolveOrCreateCustomer(chatId, chat);
        log.info("Consulta en lenguaje natural de chatId={}, customerId={}", chatId, customer.getId());

        String normalized = normalizeForIntent(text);

        Optional<String> contextualResponse = tryHandleContextualPurchase(
                chatId, chat, text, normalized, messageAlreadySent);
        if (contextualResponse.isPresent()) {
            return contextualResponse.get();
        }

        try {
            GeminiChatResult result = geminiService.chat(
                    text, customer.getId(), chatSessionService.buildGeminiContext(chatId));
            log.info(
                    "Respuesta Gemini para chatId={}: skusSugeridos={}, lineasPendientes={}, longitudMensaje={}",
                    chatId,
                    result.suggestedSkus().size(),
                    result.pendingOrderLines().size(),
                    result.message().length());

            if (!result.suggestedSkus().isEmpty()) {
                chatSessionService.recordShownSkus(chatId, result.suggestedSkus());
            }

            if (!result.pendingOrderLines().isEmpty()) {
                return presentMultiItemOrderSummary(
                        chatId, result.pendingOrderLines(), result.message(), messageAlreadySent);
            }

            if (!result.suggestedSkus().isEmpty()) {
                telegramClientService.sendMessageWithInlineKeyboard(
                        chatId,
                        result.message(),
                        telegramClientService.buildSuggestedSkusKeyboard(result.suggestedSkus()));
                messageAlreadySent[0] = true;
            }

            return result.message();
        } catch (Exception ex) {
            log.error("Error en asistente Gemini para chatId={}", chatId, ex);
            return "Disculpa, no pude procesar tu consulta. Intenta de nuevo o usa /catalogo.";
        }
    }

    private Optional<String> tryHandleContextualPurchase(
            Long chatId,
            TelegramChatDTO chat,
            String text,
            String normalized,
            boolean[] messageAlreadySent) {

        if (chatSessionService.isAwaitingConfirmation(chatId)) {
            if (purchaseIntentResolver.isNegative(normalized)) {
                chatSessionService.clearPendingOrder(chatId);
                return Optional.of("❌ Pedido cancelado. Si quieres ajustar algo, dime qué repuestos necesitas.");
            }

            if (purchaseIntentResolver.isAffirmative(normalized)) {
                return chatSessionService.getPendingOrder(chatId)
                        .map(lines -> confirmMultiItemOrder(chatId, chat, lines, messageAlreadySent, null));
            }
        }

        if (chatSessionService.hasShownProducts(chatId)) {
            return chatSessionService.tryResolvePurchaseIntent(chatId, normalized)
                    .map(lines -> presentMultiItemOrderSummary(chatId, lines, buildInferredOrderMessage(lines), messageAlreadySent));
        }

        return Optional.empty();
    }

    private String buildInferredOrderMessage(List<PendingOrderLine> lines) {
        return """
                ¡Perfecto! Entendí que quieres estos repuestos. \
                Revisa el resumen y confirma si todo está correcto. \
                Si algo no es lo que buscabas, dime qué cambiar.""";
    }

    private String presentMultiItemOrderSummary(
            Long chatId,
            List<PendingOrderLine> lines,
            String introMessage,
            boolean[] messageAlreadySent) {

        List<ValidatedOrderLine> validatedLines = validateOrderLines(lines);
        if (validatedLines.isEmpty()) {
            return "No pude identificar los repuestos de tu pedido. ¿Puedes indicarme cuáles necesitas?";
        }

        chatSessionService.setPendingOrder(chatId, toPendingOrderLines(validatedLines));

        String summaryMessage = buildMultiOrderSummaryMessage(introMessage, validatedLines);
        Map<String, Object> keyboard =
                telegramClientService.buildMultiOrderConfirmationKeyboard(toPendingOrderLines(validatedLines));

        telegramClientService.sendMessageWithInlineKeyboard(chatId, summaryMessage, keyboard);
        messageAlreadySent[0] = true;

        log.info("Resumen multi-producto enviado. chatId={}, items={}", chatId, validatedLines.size());
        return summaryMessage;
    }

    private String confirmMultiItemOrder(
            Long chatId,
            TelegramChatDTO chat,
            List<PendingOrderLine> lines,
            boolean[] messageAlreadySent,
            Long messageIdToEdit) {

        Customer customer = resolveOrCreateCustomer(chatId, chat);
        List<ValidatedOrderLine> validatedLines = validateOrderLines(lines);

        if (validatedLines.isEmpty()) {
            throw new IllegalArgumentException("No hay repuestos válidos para confirmar el pedido.");
        }

        List<OrderItemRequestDTO> orderItems = validatedLines.stream()
                .map(line -> OrderItemRequestDTO.builder()
                        .productVariantId(line.variant().getId())
                        .quantity(line.quantity())
                        .build())
                .toList();

        OrderDTO order = orderService.createOrder(customer.getId(), orderItems);
        chatSessionService.clearPendingOrder(chatId);

        log.info("Orden multi-producto confirmada. orderId={}, chatId={}, items={}",
                order.getId(), chatId, orderItems.size());

        String successMessage = buildOrderSuccessMessage(order);
        if (messageIdToEdit != null) {
            telegramClientService.editMessageText(chatId, messageIdToEdit, successMessage);
        } else {
            telegramClientService.sendMessage(chatId, successMessage);
            messageAlreadySent[0] = true;
        }
        return successMessage;
    }

    private String handleConfirmMultiOrder(
            Long chatId, Long messageId, TelegramChatDTO chat, List<PendingOrderLine> lines) {
        boolean[] messageAlreadySent = {false};
        return confirmMultiItemOrder(chatId, chat, lines, messageAlreadySent, messageId);
    }

    private List<ValidatedOrderLine> validateOrderLines(List<PendingOrderLine> lines) {
        List<ValidatedOrderLine> validated = new ArrayList<>();
        for (PendingOrderLine line : lines) {
            ProductVariant variant = productVariantRepository.findBySku(line.sku())
                    .filter(v -> Boolean.TRUE.equals(v.getIsActive()))
                    .orElse(null);
            if (variant == null) {
                continue;
            }
            if (line.quantity() > variant.getStock()) {
                throw new InsufficientStockException(
                        "Stock insuficiente para SKU: " + line.sku() + ". Disponible: " + variant.getStock());
            }
            validated.add(new ValidatedOrderLine(variant, line.quantity()));
        }
        return validated;
    }

    private String buildMultiOrderSummaryMessage(String introMessage, List<ValidatedOrderLine> lines) {
        StringBuilder summary = new StringBuilder();
        if (introMessage != null && !introMessage.isBlank()) {
            summary.append(introMessage.trim()).append("\n\n");
        }

        summary.append("🛒 *Resumen de tu Pedido*\n\n");
        BigDecimal total = BigDecimal.ZERO;

        for (ValidatedOrderLine line : lines) {
            ProductVariant variant = line.variant();
            Product product = variant.getProduct();
            String productName = product != null ? product.getName() : variant.getSku();
            BigDecimal unitPrice = resolveVariantPrice(variant, product);
            BigDecimal subtotal = unitPrice.multiply(BigDecimal.valueOf(line.quantity()));
            total = total.add(subtotal);

            summary.append("🚗 *").append(productName).append("*\n");
            summary.append("• SKU: `").append(variant.getSku()).append("`\n");
            summary.append("• Cantidad: ").append(line.quantity()).append("\n");
            summary.append("• Subtotal: ").append(formatCop(subtotal)).append("\n\n");
        }

        summary.append("• *Total:* ").append(formatCop(total)).append("\n\n");
        summary.append("¿Confirmas este pedido? Si algo no es correcto, dime qué ajustar.");
        return summary.toString().trim();
    }

    private List<PendingOrderLine> toPendingOrderLines(List<ValidatedOrderLine> lines) {
        return lines.stream()
                .map(line -> new PendingOrderLine(line.variant().getSku(), line.quantity()))
                .toList();
    }

    private String encodeMultiOrderPayload(List<PendingOrderLine> lines) {
        return lines.stream()
                .map(line -> line.sku() + ":" + line.quantity())
                .reduce((left, right) -> left + "," + right)
                .orElse("");
    }

    private List<PendingOrderLine> decodeMultiOrderPayload(String payload) {
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("Pedido inválido.");
        }

        List<PendingOrderLine> lines = new ArrayList<>();
        for (String entry : payload.split(",")) {
            String[] parts = entry.split(":", 2);
            if (parts.length < 2) {
                throw new IllegalArgumentException("Pedido inválido.");
            }
            lines.add(new PendingOrderLine(parts[0].trim().toUpperCase(), parsePositiveQuantity(parts[1])));
        }
        return lines;
    }

    private record ValidatedOrderLine(ProductVariant variant, int quantity) {}

    private String startPurchaseFromSku(
            Long chatId, Long messageId, TelegramChatDTO chat, String sku, boolean editExistingMessage) {
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
        String selectionMessage = buildQuantitySelectionMessage(productName, variant, sku, variant.getStock());
        Map<String, Object> keyboard = telegramClientService.buildQuantitySelectorKeyboard(sku);

        if (editExistingMessage) {
            telegramClientService.editMessageTextWithInlineKeyboard(chatId, messageId, selectionMessage, keyboard);
        } else {
            telegramClientService.sendMessageWithInlineKeyboard(chatId, selectionMessage, keyboard);
        }

        log.info("Flujo de compra iniciado vía botón inline. sku={}, chatId={}", sku, chatId);
        return selectionMessage;
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
            String selectionMessage = buildQuantitySelectionMessage(productName, variant, sku, variant.getStock());
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

    private String buildQuantitySelectionMessage(
            String productName, ProductVariant variant, String sku, int availableStock) {
        return """
                🛒 *Selecciona la cantidad*
                🚗 *%s*
                • Aplicación: %s
                • SKU: `%s`
                • Stock disponible: %d unidades

                Elige una opción rápida o usa /comprar %s [CANTIDAD]"""
                .formatted(productName, resolveApplication(variant), sku, availableStock, sku);
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
            case "/help" -> "help";
            case "/catalogo" -> "catalogo";
            case "/comprar" -> "comprar";
            default -> "unknown";
        };

        meterRegistry.counter(MetricsConfig.TELEGRAM_COMMANDS_METRIC, "command", commandTag).increment();
    }
}
