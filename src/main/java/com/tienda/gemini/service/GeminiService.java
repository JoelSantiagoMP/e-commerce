package com.tienda.gemini.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tienda.dto.OrderDTO;
import com.tienda.dto.OrderItemRequestDTO;
import com.tienda.entity.Product;
import com.tienda.entity.ProductVariant;
import com.tienda.exception.InsufficientStockException;
import com.tienda.exception.ResourceNotFoundException;
import com.tienda.gemini.config.GeminiProperties;
import com.tienda.gemini.dto.GeminiChatResult;
import com.tienda.telegram.dto.PendingOrderLine;
import com.tienda.gemini.exception.GeminiRateLimitException;
import com.tienda.dto.CategoryDTO;
import com.tienda.dto.ProductDTO;
import com.tienda.dto.ProductVariantDTO;
import com.tienda.repository.ProductVariantRepository;
import com.tienda.service.OrderService;
import com.tienda.service.ProductService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Slf4j
@Service
@RequiredArgsConstructor
public class GeminiService {

    private static final int MAX_FUNCTION_CALL_ITERATIONS = 5;

    private static final String SYSTEM_INSTRUCTION = """
            Eres el Asesor Comercial Virtual de Autorepuestos Demo.
            Tono: comercial, servicial, claro y profesional. Explica con naturalidad cómo realizar pedidos sin recortar contexto útil.
            Usa emojis automotrices (🚗, 🛠️, 📦) con moderación.

            Idioma: español.
            Formato de precios: siempre en Pesos Colombianos (COP), ej: *$85.000 COP*.

            Formato OBLIGATORIO para cada repuesto (catálogo, consultas o recomendaciones):

            🚗 **[Nombre del Producto]**
            • Aplicación: [Modelos de vehículo compatibles]
            • SKU: `[SKU]`
            • Precio: $[Monto] COP
            • Disponibilidad: [Unidades] unidades

            Reglas de presentación:
            - Nunca entregues bloques densos de texto; usa viñetas y negritas para SKUs y precios.
            - SIEMPRE incluye el campo Aplicación/compatibilidad vehicular cuando la herramienta lo provea.
            - Al finalizar un catálogo o recomendación, invita al cliente a confirmar en lenguaje natural \
              (por ejemplo "los quiero", "dame ambos") o a tocar el botón 🛒. No exijas que escriba el SKU.

            Reglas de negocio:
            - Para consultar disponibilidad o precios, invoca SIEMPRE consultarStock(sku).
            - Para mostrar el catálogo o buscar repuestos por vehículo/nombre sin SKU, invoca SIEMPRE listarCatalogo().
            - NUNCA inventes precios, stock, SKUs ni compatibilidades no provistos por las herramientas.
            - Si el cliente confirma compra con SKU y cantidad explícitos, invoca crearOrden(sku, cantidad). Si no indica cantidad, usa 1.
            - Si el cliente confirma productos ya mostrados (por ejemplo "ambos", "los 2", "los que me mostraste", "sí los quiero"),
              NUNCA pidas el SKU. Resume el pedido e invoca prepararPedido con los SKUs inferidos y cantidad 1 por defecto
              (o la cantidad que el cliente haya indicado).
            - Cuando el cliente confirme un pedido pendiente ("sí", "dale", "confirmo"), invoca confirmarPedido.
            - Si una herramienta devuelve error=true, comunica el mensaje al cliente sin inventar datos.
            - Si un producto tiene stock 0, indícalo como no disponible y no lo ofrezcas en el catálogo.
            """;

    private final RestClient geminiRestClient;
    private final GeminiProperties geminiProperties;
    private final ProductVariantRepository productVariantRepository;
    private final ProductService productService;
    private final OrderService orderService;
    private final ObjectMapper objectMapper;

    public GeminiChatResult chat(String userMessage, Long customerId) {
        return chat(userMessage, customerId, "");
    }

    public GeminiChatResult chat(String userMessage, Long customerId, String conversationContext) {
        if (userMessage == null || userMessage.isBlank()) {
            return GeminiChatResult.textOnly("Por favor, escribe tu consulta.");
        }

        List<String> suggestedSkus = new ArrayList<>();
        List<PendingOrderLine> pendingOrderLines = new ArrayList<>();
        ArrayNode contents = objectMapper.createArrayNode();
        contents.add(buildUserContent(buildUserMessageWithContext(userMessage.trim(), conversationContext)));

        for (int iteration = 0; iteration < MAX_FUNCTION_CALL_ITERATIONS; iteration++) {
            JsonNode response;
            try {
                response = invokeGenerateContent(contents);
            } catch (GeminiRateLimitException ex) {
                log.warn("Cuota de Gemini agotada (429) en iteración {}", iteration);
                return GeminiChatResult.textOnly(
                        "Estoy recibiendo muchas consultas en este momento 🤖. Por favor intenta de nuevo en unos segundos o usa el comando /catalogo.");
            } catch (IllegalStateException ex) {
                log.error("Fallo externo al comunicarse con Gemini", ex);
                return GeminiChatResult.textOnly(
                        "Disculpa, estoy teniendo dificultades técnicas. Intenta de nuevo en unos momentos o usa /catalogo.");
            }
            JsonNode candidate = firstCandidate(response);

            if (candidate == null) {
                log.warn("Gemini no devolvió candidatos. response={}", response);
                return GeminiChatResult.textOnly("No pude procesar tu solicitud en este momento. Intenta de nuevo.");
            }

            JsonNode modelContent = candidate.path("content");
            ArrayNode modelParts = extractParts(modelContent);
            if (modelParts.isEmpty()) {
                return GeminiChatResult.textOnly("No recibí una respuesta válida del asistente.");
            }

            List<JsonNode> functionCalls = extractFunctionCalls(modelParts);
            if (functionCalls.isEmpty()) {
                String text = extractTextResponse(modelParts).orElse("No pude generar una respuesta.");
                return new GeminiChatResult(text, List.copyOf(suggestedSkus), List.copyOf(pendingOrderLines));
            }

            contents.add(modelContent);

            ArrayNode functionResponseParts = objectMapper.createArrayNode();
            for (JsonNode functionCall : functionCalls) {
                String functionName = functionCall.path("name").asText();
                JsonNode args = functionCall.has("args") ? functionCall.path("args") : objectMapper.createObjectNode();

                Map<String, Object> result;
                try {
                    log.info("Ejecutando function call: {} args={}", functionName, args);
                    result = executeFunction(functionName, args, customerId);
                } catch (Exception ex) {
                    log.error(
                            "Error inesperado al ejecutar function call {} con args={}",
                            functionName,
                            args,
                            ex);
                    result = toolFailureResult(resolveToolFailureMessage(functionName, ex));
                }

                collectSuggestedSkus(functionName, result, suggestedSkus);
                collectPendingOrderLines(functionName, result, pendingOrderLines);

                ObjectNode functionResponse = objectMapper.createObjectNode();
                functionResponse.put("name", functionName);
                functionResponse.set("response", objectMapper.valueToTree(sanitizeFunctionResult(result)));
                functionResponseParts.add(objectMapper.createObjectNode().set("functionResponse", functionResponse));
            }

            ObjectNode functionResponseContent = objectMapper.createObjectNode();
            functionResponseContent.put("role", "user");
            functionResponseContent.set("parts", functionResponseParts);
            contents.add(functionResponseContent);
        }

        return GeminiChatResult.textOnly("Se alcanzó el límite de operaciones. Intenta simplificar tu solicitud.");
    }

    @SuppressWarnings("unchecked")
    private void collectPendingOrderLines(
            String functionName, Map<String, Object> result, List<PendingOrderLine> pendingOrderLines) {
        if (Boolean.TRUE.equals(result.get("error"))) {
            return;
        }
        if (!"prepararPedido".equals(functionName) || !(result.get("items") instanceof List<?> items)) {
            return;
        }
        pendingOrderLines.clear();
        for (Object item : items) {
            if (item instanceof Map<?, ?> map && map.get("sku") != null) {
                int quantity = map.get("quantity") instanceof Number number ? number.intValue() : 1;
                pendingOrderLines.add(new PendingOrderLine(map.get("sku").toString(), quantity));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void collectSuggestedSkus(String functionName, Map<String, Object> result, List<String> suggestedSkus) {
        if (Boolean.TRUE.equals(result.get("error"))) {
            return;
        }
        if ("consultarStock".equals(functionName) && result.get("sku") != null) {
            suggestedSkus.add(result.get("sku").toString());
        }
        if ("listarCatalogo".equals(functionName) && result.get("items") instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof Map<?, ?> map && map.get("sku") != null) {
                    suggestedSkus.add(map.get("sku").toString());
                }
            }
        }
        if ("prepararPedido".equals(functionName) && result.get("items") instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof Map<?, ?> map && map.get("sku") != null) {
                    suggestedSkus.add(map.get("sku").toString());
                }
            }
        }
        if ("crearOrden".equals(functionName) && result.get("sku") != null) {
            suggestedSkus.add(result.get("sku").toString());
        }
    }

    Map<String, Object> executeFunction(String functionName, JsonNode args, Long customerId) {
        try {
            return switch (functionName) {
                case "consultarStock" -> consultarStock(extractStringArg(args, "sku"));
                case "listarCatalogo" -> listarCatalogo();
                case "crearOrden" -> crearOrden(
                        extractStringArg(args, "sku"),
                        extractIntArg(args, "cantidad", 1),
                        customerId);
                case "prepararPedido" -> prepararPedido(extractOrderItems(args));
                case "confirmarPedido" -> confirmarPedido(extractOrderItems(args), customerId);
                default -> toolFailureResult("Función no soportada: " + functionName);
            };
        } catch (ResourceNotFoundException ex) {
            log.warn("Function call {}: recurso no encontrado - {}", functionName, ex.getMessage());
            return toolFailureResult(ex.getMessage());
        } catch (InsufficientStockException ex) {
            log.warn("Function call {}: stock insuficiente - {}", functionName, ex.getMessage());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("error", true);
            result.put("insufficientStock", true);
            result.put("message", ex.getMessage());
            return result;
        } catch (IllegalArgumentException ex) {
            log.warn("Function call {}: argumento inválido - {}", functionName, ex.getMessage());
            return toolFailureResult(ex.getMessage());
        } catch (Exception ex) {
            log.error("Function call {} falló con args={}", functionName, args, ex);
            return toolFailureResult(resolveToolFailureMessage(functionName, ex));
        }
    }

    private Map<String, Object> toolFailureResult(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("error", true);
        result.put("message", message);
        return result;
    }

    private String resolveToolFailureMessage(String functionName, Exception ex) {
        return switch (functionName) {
            case "consultarStock" -> "No pude verificar el stock en este momento. Intenta de nuevo o usa /catalogo.";
            case "listarCatalogo" -> "No pude consultar el catálogo en este momento. Usa /catalogo para ver los repuestos disponibles.";
            case "crearOrden", "confirmarPedido" ->
                    "No pude registrar tu pedido en este momento. Intenta con /comprar SKU o más tarde.";
            case "prepararPedido" ->
                    "No pude preparar tu pedido en este momento. Intenta de nuevo o usa /catalogo.";
            default -> "Ocurrió un problema al procesar tu solicitud. Intenta de nuevo en unos momentos.";
        };
    }

    private String extractStringArg(JsonNode args, String fieldName) {
        JsonNode value = args.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            throw new IllegalArgumentException("El parámetro '" + fieldName + "' es obligatorio.");
        }
        String text = value.asText().trim();
        if (text.isBlank()) {
            throw new IllegalArgumentException("El parámetro '" + fieldName + "' no puede estar vacío.");
        }
        return text;
    }

    private int extractIntArg(JsonNode args, String fieldName, int defaultValue) {
        JsonNode value = args.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            return defaultValue;
        }
        if (value.isNumber()) {
            return value.intValue();
        }
        if (value.isTextual()) {
            try {
                return Integer.parseInt(value.asText().trim());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException(
                        "El parámetro '" + fieldName + "' debe ser un entero positivo.");
            }
        }
        throw new IllegalArgumentException("El parámetro '" + fieldName + "' debe ser un entero positivo.");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sanitizeFunctionResult(Map<String, Object> result) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : result.entrySet()) {
            sanitized.put(entry.getKey(), sanitizeFunctionValue(entry.getValue()));
        }
        return sanitized;
    }

    @SuppressWarnings("unchecked")
    private Object sanitizeFunctionValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.doubleValue();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sanitized.put(String.valueOf(entry.getKey()), sanitizeFunctionValue(entry.getValue()));
            }
            return sanitized;
        }
        if (value instanceof List<?> list) {
            List<Object> sanitized = new ArrayList<>();
            for (Object item : list) {
                sanitized.add(sanitizeFunctionValue(item));
            }
            return sanitized;
        }
        return value;
    }

    private Map<String, Object> listarCatalogo() {
        try {
            List<Map<String, Object>> items = new ArrayList<>();

            for (CategoryDTO category : productService.getAllActiveCategories()) {
                for (ProductDTO product : productService.getActiveProductsByCategory(category.getId())) {
                    for (ProductVariantDTO variant : productService.getActiveVariantsByProductId(product.getId())) {
                        if (variant.getStock() == null || variant.getStock() <= 0) {
                            continue;
                        }

                        BigDecimal price = variant.getPriceOverride() != null
                                ? variant.getPriceOverride()
                                : product.getBasePrice();

                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("category", category.getName());
                        item.put("productName", product.getName());
                        item.put("sku", variant.getSku());
                        item.put("stock", variant.getStock());
                        item.put("price", price);
                        item.put("application", resolveApplication(variant));
                        item.put("position", variant.getSize());
                        item.put("compatibility", variant.getColor());
                        items.add(item);
                    }
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("items", items);
            result.put("totalAvailable", items.size());
            if (items.isEmpty()) {
                result.put("message", "No hay repuestos con stock disponible en este momento.");
            }
            return result;
        } catch (Exception ex) {
            log.error("Error al listar catálogo para function call listarCatalogo", ex);
            throw new IllegalStateException("No pude consultar el catálogo en este momento.", ex);
        }
    }

    private Map<String, Object> consultarStock(String rawSku) {
        try {
            ProductVariant variant = findActiveVariant(rawSku);
            Product product = variant.getProduct();
            BigDecimal price = resolveUnitPrice(variant, product);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("sku", variant.getSku());
            result.put("productName", product != null ? product.getName() : variant.getSku());
            result.put("stock", variant.getStock());
            result.put("price", price);
            result.put("available", variant.getStock() > 0);
            result.put("application", resolveApplication(variant));
            result.put("position", variant.getSize());
            result.put("compatibility", variant.getColor());
            return result;
        } catch (ResourceNotFoundException | IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Error al consultar stock para SKU={}", rawSku, ex);
            throw new IllegalStateException("No pude verificar el stock en este momento.", ex);
        }
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

    private Map<String, Object> prepararPedido(List<PendingOrderLine> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("Debes indicar al menos un repuesto para el pedido.");
        }

        List<Map<String, Object>> validatedItems = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;

        for (PendingOrderLine line : items) {
            ProductVariant variant = findActiveVariant(line.sku());
            Product product = variant.getProduct();
            BigDecimal unitPrice = resolveUnitPrice(variant, product);

            if (variant.getStock() < line.quantity()) {
                throw new InsufficientStockException(
                        "Stock insuficiente para SKU: " + variant.getSku() + ". Disponible: " + variant.getStock());
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sku", variant.getSku());
            item.put("productName", product != null ? product.getName() : variant.getSku());
            item.put("quantity", line.quantity());
            item.put("unitPrice", unitPrice);
            item.put("subtotal", unitPrice.multiply(BigDecimal.valueOf(line.quantity())));
            item.put("application", resolveApplication(variant));
            validatedItems.add(item);
            total = total.add(unitPrice.multiply(BigDecimal.valueOf(line.quantity())));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", validatedItems);
        result.put("totalAmount", total);
        result.put("awaitingConfirmation", true);
        result.put("message", "Pedido preparado con " + validatedItems.size() + " repuesto(s). Esperando confirmación del cliente.");
        return result;
    }

    private Map<String, Object> confirmarPedido(List<PendingOrderLine> items, Long customerId) {
        if (customerId == null) {
            throw new IllegalArgumentException("Se requiere un cliente identificado para crear la orden.");
        }
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("Debes indicar al menos un repuesto para confirmar el pedido.");
        }

        List<OrderItemRequestDTO> orderItems = new ArrayList<>();
        for (PendingOrderLine line : items) {
            ProductVariant variant = findActiveVariant(line.sku());
            if (variant.getStock() < line.quantity()) {
                throw new InsufficientStockException(
                        "Stock insuficiente para SKU: " + variant.getSku() + ". Disponible: " + variant.getStock());
            }
            orderItems.add(OrderItemRequestDTO.builder()
                    .productVariantId(variant.getId())
                    .quantity(line.quantity())
                    .build());
        }

        OrderDTO order = orderService.createOrder(customerId, orderItems);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", order.getId());
        result.put("status", order.getStatus().name());
        result.put("totalAmount", order.getTotalAmount());
        result.put("itemCount", orderItems.size());
        result.put("message", "Orden creada exitosamente con ID " + order.getId());
        return result;
    }

    private List<PendingOrderLine> extractOrderItems(JsonNode args) {
        JsonNode itemsNode = args.path("items");
        if (!itemsNode.isArray() || itemsNode.isEmpty()) {
            throw new IllegalArgumentException("Debes indicar al menos un repuesto en 'items'.");
        }

        List<PendingOrderLine> items = new ArrayList<>();
        for (JsonNode itemNode : itemsNode) {
            String sku = extractStringArg(itemNode, "sku");
            int quantity = extractIntArg(itemNode, "cantidad", 1);
            if (quantity <= 0) {
                throw new IllegalArgumentException("La cantidad debe ser un entero positivo.");
            }
            items.add(new PendingOrderLine(sku.trim().toUpperCase(), quantity));
        }
        return items;
    }

    private Map<String, Object> crearOrden(String rawSku, int cantidad, Long customerId) {
        try {
            if (customerId == null) {
                throw new IllegalArgumentException("Se requiere un cliente identificado para crear la orden.");
            }
            if (cantidad <= 0) {
                throw new IllegalArgumentException("La cantidad debe ser un entero positivo.");
            }

            ProductVariant variant = findActiveVariant(rawSku);

            if (variant.getStock() < cantidad) {
                throw new InsufficientStockException(
                        "Stock insuficiente para SKU: " + variant.getSku() + ". Disponible: " + variant.getStock());
            }

            OrderDTO order = orderService.createOrder(
                    customerId,
                    List.of(OrderItemRequestDTO.builder()
                            .productVariantId(variant.getId())
                            .quantity(cantidad)
                            .build()));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("orderId", order.getId());
            result.put("status", order.getStatus().name());
            result.put("totalAmount", order.getTotalAmount());
            result.put("sku", variant.getSku());
            result.put("quantity", cantidad);
            result.put("message", "Orden creada exitosamente con ID " + order.getId());
            return result;
        } catch (ResourceNotFoundException | InsufficientStockException | IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Error al crear orden para SKU={}, cantidad={}, customerId={}", rawSku, cantidad, customerId, ex);
            throw new IllegalStateException("No pude registrar tu pedido en este momento.", ex);
        }
    }

    private ProductVariant findActiveVariant(String rawSku) {
        if (rawSku == null || rawSku.isBlank()) {
            throw new IllegalArgumentException("El SKU es obligatorio.");
        }

        String sku = rawSku.trim().toUpperCase();
        return productVariantRepository.findBySku(sku)
                .filter(variant -> Boolean.TRUE.equals(variant.getIsActive()))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Repuesto no encontrado o inactivo con SKU: " + sku));
    }

    private BigDecimal resolveUnitPrice(ProductVariant variant, Product product) {
        if (variant.getPriceOverride() != null) {
            return variant.getPriceOverride();
        }
        if (product != null && product.getBasePrice() != null) {
            return product.getBasePrice();
        }
        return BigDecimal.ZERO;
    }

    private JsonNode invokeGenerateContent(ArrayNode contents) {
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.set("contents", contents);
        requestBody.set("tools", buildToolsDefinition());
        requestBody.set("systemInstruction", objectMapper.createObjectNode()
                .put("role", "system")
                .set("parts", objectMapper.createArrayNode()
                        .add(objectMapper.createObjectNode().put("text", SYSTEM_INSTRUCTION))));

        String endpoint = "/" + geminiProperties.getModel() + ":generateContent?key=" + geminiProperties.getKey();

        try {
            return geminiRestClient.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException ex) {
            log.error(
                    "Error en llamada a Gemini API: status={}, body={}",
                    ex.getStatusCode(),
                    ex.getResponseBodyAsString(),
                    ex);
            if (ex.getStatusCode().value() == HttpStatus.TOO_MANY_REQUESTS.value()) {
                throw new GeminiRateLimitException("Cuota de Gemini agotada (429)", ex);
            }
            if (ex.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                throw new IllegalStateException(
                        "Modelo Gemini no disponible: " + geminiProperties.getModel()
                                + ". Verifica GEMINI_MODEL en la configuración.",
                        ex);
            }
            throw new IllegalStateException("Error al comunicarse con Gemini: " + ex.getStatusText(), ex);
        } catch (Exception ex) {
            log.error("Error inesperado al invocar Gemini API con modelo {}", geminiProperties.getModel(), ex);
            throw new IllegalStateException("Error al comunicarse con Gemini.", ex);
        }
    }

    private ArrayNode buildToolsDefinition() {
        ObjectNode consultarStockParams = objectMapper.createObjectNode();
        consultarStockParams.put("type", "OBJECT");
        consultarStockParams.set("properties", objectMapper.createObjectNode()
                .set("sku", objectMapper.createObjectNode()
                        .put("type", "STRING")
                        .put("description", "Código SKU del repuesto, por ejemplo FRN-CHE-001")));
        consultarStockParams.set("required", objectMapper.createArrayNode().add("sku"));

        ObjectNode consultarStock = objectMapper.createObjectNode();
        consultarStock.put("name", "consultarStock");
        consultarStock.put("description", "Consulta el stock disponible y precio de un repuesto por su SKU.");
        consultarStock.set("parameters", consultarStockParams);

        ObjectNode listarCatalogoParams = objectMapper.createObjectNode();
        listarCatalogoParams.put("type", "OBJECT");
        listarCatalogoParams.set("properties", objectMapper.createObjectNode());

        ObjectNode listarCatalogo = objectMapper.createObjectNode();
        listarCatalogo.put("name", "listarCatalogo");
        listarCatalogo.put("description", "Lista el catálogo de repuestos con stock disponible (excluye stock cero).");
        listarCatalogo.set("parameters", listarCatalogoParams);

        ObjectNode crearOrdenProperties = objectMapper.createObjectNode();
        crearOrdenProperties.set("sku", objectMapper.createObjectNode()
                .put("type", "STRING")
                .put("description", "Código SKU del repuesto"));
        crearOrdenProperties.set("cantidad", objectMapper.createObjectNode()
                .put("type", "INTEGER")
                .put("description", "Cantidad de unidades a pedir (por defecto 1 si no se indica)"));

        ObjectNode crearOrdenParams = objectMapper.createObjectNode();
        crearOrdenParams.put("type", "OBJECT");
        crearOrdenParams.set("properties", crearOrdenProperties);
        crearOrdenParams.set("required", objectMapper.createArrayNode().add("sku"));

        ObjectNode crearOrden = objectMapper.createObjectNode();
        crearOrden.put("name", "crearOrden");
        crearOrden.put("description", "Crea una orden de compra para un repuesto identificado por SKU.");
        crearOrden.set("parameters", crearOrdenParams);

        ObjectNode orderItemSchema = objectMapper.createObjectNode();
        orderItemSchema.set("sku", objectMapper.createObjectNode()
                .put("type", "STRING")
                .put("description", "Código SKU del repuesto"));
        orderItemSchema.set("cantidad", objectMapper.createObjectNode()
                .put("type", "INTEGER")
                .put("description", "Cantidad de unidades (por defecto 1)"));

        ObjectNode orderItemObject = objectMapper.createObjectNode();
        orderItemObject.put("type", "OBJECT");
        orderItemObject.set("properties", orderItemSchema);
        orderItemObject.set("required", objectMapper.createArrayNode().add("sku"));

        ObjectNode orderItemsArray = objectMapper.createObjectNode();
        orderItemsArray.put("type", "ARRAY");
        orderItemsArray.put("description", "Lista de repuestos del pedido");
        orderItemsArray.set("items", orderItemObject);

        ObjectNode multiOrderProperties = objectMapper.createObjectNode();
        multiOrderProperties.set("items", orderItemsArray);

        ObjectNode multiOrderParams = objectMapper.createObjectNode();
        multiOrderParams.put("type", "OBJECT");
        multiOrderParams.set("properties", multiOrderProperties);
        multiOrderParams.set("required", objectMapper.createArrayNode().add("items"));

        ObjectNode prepararPedido = objectMapper.createObjectNode();
        prepararPedido.put("name", "prepararPedido");
        prepararPedido.put(
                "description",
                "Prepara un pedido con uno o más repuestos para confirmación del cliente. No crea la orden aún.");
        prepararPedido.set("parameters", multiOrderParams);

        ObjectNode confirmarPedido = objectMapper.createObjectNode();
        confirmarPedido.put("name", "confirmarPedido");
        confirmarPedido.put(
                "description",
                "Confirma y registra un pedido con uno o más repuestos cuando el cliente acepta.");
        confirmarPedido.set("parameters", multiOrderParams);

        ObjectNode functionDeclarations = objectMapper.createObjectNode();
        functionDeclarations.set("functionDeclarations", objectMapper.createArrayNode()
                .add(consultarStock)
                .add(listarCatalogo)
                .add(crearOrden)
                .add(prepararPedido)
                .add(confirmarPedido));

        return objectMapper.createArrayNode().add(functionDeclarations);
    }

    private String buildUserMessageWithContext(String userMessage, String conversationContext) {
        if (conversationContext == null || conversationContext.isBlank()) {
            return userMessage;
        }
        return """
                [Contexto de la conversación]
                %s

                [Mensaje del cliente]
                %s"""
                .formatted(conversationContext.trim(), userMessage);
    }

    private ObjectNode buildUserContent(String userMessage) {
        ObjectNode content = objectMapper.createObjectNode();
        content.put("role", "user");
        content.set("parts", objectMapper.createArrayNode()
                .add(objectMapper.createObjectNode().put("text", userMessage)));
        return content;
    }

    private JsonNode firstCandidate(JsonNode response) {
        JsonNode candidates = response.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            return null;
        }
        return candidates.get(0);
    }

    private ArrayNode extractParts(JsonNode content) {
        JsonNode parts = content.path("parts");
        if (parts.isArray()) {
            return (ArrayNode) parts;
        }
        return objectMapper.createArrayNode();
    }

    private List<JsonNode> extractFunctionCalls(ArrayNode parts) {
        List<JsonNode> functionCalls = new ArrayList<>();
        for (JsonNode part : parts) {
            if (part.has("functionCall")) {
                functionCalls.add(part.path("functionCall"));
            }
        }
        return functionCalls;
    }

    private java.util.Optional<String> extractTextResponse(ArrayNode parts) {
        StringBuilder text = new StringBuilder();
        for (JsonNode part : parts) {
            if (part.has("text")) {
                if (!text.isEmpty()) {
                    text.append("\n");
                }
                text.append(part.path("text").asText());
            }
        }
        return text.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(text.toString().trim());
    }
}
