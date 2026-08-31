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
            Usa un tono cordial y emojis automotrices (🚗, 🛠️, 📦).
            Nunca entregues bloques densos de texto. Usa siempre listas con viñetas y negritas para resaltar SKUs y precios.

            Idioma: español.
            Formato de precios: siempre en Pesos Colombianos (COP), ej: *$85.000 COP*.

            Reglas de negocio:
            - Para consultar disponibilidad o precios, invoca SIEMPRE consultarStock(sku).
            - Para mostrar el catálogo, invoca SIEMPRE listarCatalogo().
            - NUNCA inventes precios, stock ni SKUs no provistos por las herramientas.
            - Si el cliente confirma compra con SKU y cantidad, invoca crearOrden(sku, cantidad).
            - La cantidad puede ser cualquier entero positivo; valida stock antes de confirmar.
            - Si una herramienta devuelve error=true, comunica el mensaje al cliente sin inventar datos.
            - Si un producto tiene stock 0, indícalo como no disponible y no lo ofrezcas en el catálogo.
            """;

    private final RestClient geminiRestClient;
    private final GeminiProperties geminiProperties;
    private final ProductVariantRepository productVariantRepository;
    private final ProductService productService;
    private final OrderService orderService;
    private final ObjectMapper objectMapper;

    public String chat(String userMessage, Long customerId) {
        if (userMessage == null || userMessage.isBlank()) {
            return "Por favor, escribe tu consulta.";
        }

        ArrayNode contents = objectMapper.createArrayNode();
        contents.add(buildUserContent(userMessage.trim()));

        for (int iteration = 0; iteration < MAX_FUNCTION_CALL_ITERATIONS; iteration++) {
            JsonNode response;
            try {
                response = invokeGenerateContent(contents);
            } catch (GeminiRateLimitException ex) {
                log.warn("Cuota de Gemini agotada (429) en iteración {}", iteration);
                return "Estoy recibiendo muchas consultas en este momento 🤖. Por favor intenta de nuevo en unos segundos o usa el comando /catalogo.";
            } catch (IllegalStateException ex) {
                log.error("Fallo externo al comunicarse con Gemini", ex);
                return "Disculpa, estoy teniendo dificultades técnicas. Intenta de nuevo en unos momentos o usa /catalogo.";
            }
            JsonNode candidate = firstCandidate(response);

            if (candidate == null) {
                log.warn("Gemini no devolvió candidatos. response={}", response);
                return "No pude procesar tu solicitud en este momento. Intenta de nuevo.";
            }

            JsonNode modelContent = candidate.path("content");
            ArrayNode modelParts = extractParts(modelContent);
            if (modelParts.isEmpty()) {
                return "No recibí una respuesta válida del asistente.";
            }

            List<JsonNode> functionCalls = extractFunctionCalls(modelParts);
            if (functionCalls.isEmpty()) {
                return extractTextResponse(modelParts)
                        .orElse("No pude generar una respuesta.");
            }

            contents.add(modelContent);

            ArrayNode functionResponseParts = objectMapper.createArrayNode();
            for (JsonNode functionCall : functionCalls) {
                String functionName = functionCall.path("name").asText();
                JsonNode args = functionCall.path("args");
                Map<String, Object> result = executeFunction(functionName, args, customerId);

                ObjectNode functionResponse = objectMapper.createObjectNode();
                functionResponse.put("name", functionName);
                functionResponse.set("response", objectMapper.valueToTree(result));
                functionResponseParts.add(objectMapper.createObjectNode().set("functionResponse", functionResponse));
            }

            ObjectNode functionResponseContent = objectMapper.createObjectNode();
            functionResponseContent.put("role", "user");
            functionResponseContent.set("parts", functionResponseParts);
            contents.add(functionResponseContent);
        }

        return "Se alcanzó el límite de operaciones. Intenta simplificar tu solicitud.";
    }

    Map<String, Object> executeFunction(String functionName, JsonNode args, Long customerId) {
        try {
            return switch (functionName) {
                case "consultarStock" -> consultarStock(args.path("sku").asText());
                case "listarCatalogo" -> listarCatalogo();
                case "crearOrden" -> crearOrden(
                        args.path("sku").asText(),
                        args.path("cantidad").asInt(1),
                        customerId);
                default -> Map.of(
                        "error", true,
                        "message", "Función no soportada: " + functionName);
            };
        } catch (ResourceNotFoundException ex) {
            return Map.of("error", true, "message", ex.getMessage());
        } catch (InsufficientStockException ex) {
            return Map.of("error", true, "insufficientStock", true, "message", ex.getMessage());
        } catch (IllegalArgumentException ex) {
            return Map.of("error", true, "message", ex.getMessage());
        }
    }

    private Map<String, Object> listarCatalogo() {
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
    }

    private Map<String, Object> consultarStock(String rawSku) {
        ProductVariant variant = findActiveVariant(rawSku);
        Product product = variant.getProduct();
        BigDecimal price = resolveUnitPrice(variant, product);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sku", variant.getSku());
        result.put("productName", product != null ? product.getName() : variant.getSku());
        result.put("stock", variant.getStock());
        result.put("price", price);
        result.put("available", variant.getStock() > 0);
        result.put("size", variant.getSize());
        result.put("color", variant.getColor());
        return result;
    }

    private Map<String, Object> crearOrden(String rawSku, int cantidad, Long customerId) {
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
            log.error("Error en llamada a Gemini API: status={}, body={}", ex.getStatusCode(), ex.getResponseBodyAsString());
            if (ex.getStatusCode().value() == HttpStatus.TOO_MANY_REQUESTS.value()) {
                throw new GeminiRateLimitException("Cuota de Gemini agotada (429)", ex);
            }
            throw new IllegalStateException("Error al comunicarse con Gemini: " + ex.getStatusText(), ex);
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
                .put("description", "Cantidad de unidades a pedir"));

        ObjectNode crearOrdenParams = objectMapper.createObjectNode();
        crearOrdenParams.put("type", "OBJECT");
        crearOrdenParams.set("properties", crearOrdenProperties);
        crearOrdenParams.set("required", objectMapper.createArrayNode().add("sku").add("cantidad"));

        ObjectNode crearOrden = objectMapper.createObjectNode();
        crearOrden.put("name", "crearOrden");
        crearOrden.put("description", "Crea una orden de compra para un repuesto identificado por SKU.");
        crearOrden.set("parameters", crearOrdenParams);

        ObjectNode functionDeclarations = objectMapper.createObjectNode();
        functionDeclarations.set("functionDeclarations", objectMapper.createArrayNode()
                .add(consultarStock)
                .add(listarCatalogo)
                .add(crearOrden));

        return objectMapper.createArrayNode().add(functionDeclarations);
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
