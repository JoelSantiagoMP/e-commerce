package com.tienda.gemini.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tienda.dto.CategoryDTO;
import com.tienda.dto.OrderDTO;
import com.tienda.dto.OrderItemRequestDTO;
import com.tienda.dto.ProductDTO;
import com.tienda.dto.ProductVariantDTO;
import com.tienda.entity.OrderStatus;
import com.tienda.entity.Product;
import com.tienda.entity.ProductVariant;
import com.tienda.gemini.config.GeminiProperties;
import com.tienda.repository.ProductVariantRepository;
import com.tienda.service.OrderService;
import com.tienda.service.ProductService;
import com.tienda.telegram.dto.PendingOrderLine;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

@ExtendWith(MockitoExtension.class)
class GeminiServicePrepararPedidoTest {

    @Mock
    private RestClient geminiRestClient;

    @Mock
    private ProductVariantRepository productVariantRepository;

    @Mock
    private OrderService orderService;

    @Mock
    private ProductService productService;

    private GeminiService geminiService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        GeminiProperties geminiProperties = new GeminiProperties();
        geminiProperties.setKey("test-key");
        geminiProperties.setModel("gemini-2.5-flash");
        geminiProperties.setUrl("https://generativelanguage.googleapis.com/v1beta/models");

        objectMapper = new ObjectMapper();
        geminiService = new GeminiService(
                geminiRestClient,
                geminiProperties,
                productVariantRepository,
                productService,
                orderService,
                objectMapper);
    }

    @Test
    void executeFunction_prepararPedido_defaultsQuantityWhenMissing() {
        stubVariant("FRN-CHE-001", 1L, 15, "85000");

        ObjectNode args = objectMapper.createObjectNode();
        ArrayNode items = objectMapper.createArrayNode();
        items.add(objectMapper.createObjectNode().put("sku", "FRN-CHE-001"));
        args.set("items", items);

        Map<String, Object> result = geminiService.executeFunction("prepararPedido", args, 1L);

        assertEquals(true, result.get("awaitingConfirmation"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> validatedItems = (List<Map<String, Object>>) result.get("items");
        assertEquals(1, validatedItems.get(0).get("quantity"));
    }

    @Test
    void executeFunction_prepararPedido_defaultsQuantityWhenZeroOrNegative() {
        stubVariant("FRN-CHE-001", 1L, 15, "85000");

        ObjectNode args = objectMapper.createObjectNode();
        ArrayNode items = objectMapper.createArrayNode();
        items.add(objectMapper.createObjectNode().put("sku", "FRN-CHE-001").put("cantidad", 0));
        args.set("items", items);

        Map<String, Object> result = geminiService.executeFunction("prepararPedido", args, 1L);

        assertEquals(true, result.get("awaitingConfirmation"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> validatedItems = (List<Map<String, Object>>) result.get("items");
        assertEquals(1, validatedItems.get(0).get("quantity"));
    }

    @Test
    void executeFunction_prepararPedido_allowsPurchaseWhenQuantityEqualsStock() {
        stubVariant("FRN-CHE-001", 1L, 15, "85000");

        ObjectNode args = objectMapper.createObjectNode();
        ArrayNode items = objectMapper.createArrayNode();
        items.add(objectMapper.createObjectNode().put("sku", "FRN-CHE-001").put("cantidad", 15));
        args.set("items", items);

        Map<String, Object> result = geminiService.executeFunction("prepararPedido", args, 1L);

        assertEquals(true, result.get("awaitingConfirmation"));
        assertEquals(new BigDecimal("1275000"), result.get("totalAmount"));
    }

    @Test
    void executeFunction_prepararPedido_validatesMultipleItemsWithoutCreatingOrder() {
        stubVariant("FRN-TOY-003", 10L, 6, "145000");
        stubVariant("SUS-CHE-021", 11L, 8, "180000");

        ObjectNode args = objectMapper.createObjectNode();
        ArrayNode items = objectMapper.createArrayNode();
        items.add(objectMapper.createObjectNode().put("sku", "FRN-TOY-003").put("cantidad", 1));
        items.add(objectMapper.createObjectNode().put("sku", "SUS-CHE-021").put("cantidad", 1));
        args.set("items", items);

        Map<String, Object> result = geminiService.executeFunction("prepararPedido", args, 1L);

        assertEquals(true, result.get("awaitingConfirmation"));
        assertEquals(new BigDecimal("325000"), result.get("totalAmount"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> validatedItems = (List<Map<String, Object>>) result.get("items");
        assertEquals(2, validatedItems.size());
    }

    @Test
    void executeFunction_confirmarPedido_createsMultiItemOrder() {
        stubVariant("FRN-TOY-003", 10L, 6, "145000");
        stubVariant("SUS-CHE-021", 11L, 8, "180000");

        OrderDTO createdOrder = OrderDTO.builder()
                .id(200L)
                .status(OrderStatus.PENDING)
                .totalAmount(new BigDecimal("325000"))
                .build();
        when(orderService.createOrder(any(), any())).thenReturn(createdOrder);

        ObjectNode args = objectMapper.createObjectNode();
        ArrayNode items = objectMapper.createArrayNode();
        items.add(objectMapper.createObjectNode().put("sku", "FRN-TOY-003").put("cantidad", 1));
        items.add(objectMapper.createObjectNode().put("sku", "SUS-CHE-021").put("cantidad", 1));
        args.set("items", items);

        Map<String, Object> result = geminiService.executeFunction("confirmarPedido", args, 7L);

        ArgumentCaptor<List<OrderItemRequestDTO>> itemsCaptor = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(orderService).createOrder(org.mockito.ArgumentMatchers.eq(7L), itemsCaptor.capture());
        assertEquals(2, itemsCaptor.getValue().size());
        assertEquals(200L, result.get("orderId"));
        assertTrue(result.get("message").toString().contains("200"));
    }

    @Test
    void mergePendingOrderLine_accumulatesMultipleCalls() {
        List<PendingOrderLine> lines = new ArrayList<>();
        geminiService.mergePendingOrderLine(lines, "FRN-TOY-003", 1);
        geminiService.mergePendingOrderLine(lines, "SUS-CHE-021", 1);
        geminiService.mergePendingOrderLine(lines, "FRN-TOY-003", 2);

        assertEquals(2, lines.size());
        assertEquals("FRN-TOY-003", lines.get(0).sku());
        assertEquals(3, lines.get(0).quantity());
        assertEquals("SUS-CHE-021", lines.get(1).sku());
        assertEquals(1, lines.get(1).quantity());
    }

    @Test
    void executeFunction_buscarRepuestos_findsProductsByVehicleAndName() {
        CategoryDTO category = CategoryDTO.builder().id(1L).name("Frenos").isActive(true).build();
        ProductDTO product = ProductDTO.builder()
                .id(10L)
                .categoryId(1L)
                .name("Pastillas de Freno Cerámicas")
                .basePrice(new BigDecimal("145000"))
                .isActive(true)
                .build();
        ProductVariantDTO fortuner = ProductVariantDTO.builder()
                .sku("FRN-TOY-003")
                .color("Hilux 2.4 / Fortuner")
                .stock(6)
                .isActive(true)
                .build();

        when(productService.getAllActiveCategories()).thenReturn(List.of(category));
        when(productService.getActiveProductsByCategory(1L)).thenReturn(List.of(product));
        when(productService.getActiveVariantsByProductId(10L)).thenReturn(List.of(fortuner));

        ObjectNode args = objectMapper.createObjectNode().put("consulta", "pastillas Fortuner");
        Map<String, Object> result = geminiService.executeFunction("buscarRepuestos", args, 1L);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        assertEquals(1, items.size());
        assertEquals("FRN-TOY-003", items.get(0).get("sku"));
    }

    private void stubVariant(String sku, long id, int stock, String price) {
        Product product = Product.builder()
                .name("Producto " + sku)
                .basePrice(new BigDecimal(price))
                .build();

        ProductVariant variant = ProductVariant.builder()
                .id(id)
                .sku(sku)
                .stock(stock)
                .isActive(true)
                .product(product)
                .build();

        when(productVariantRepository.findBySku(sku)).thenReturn(Optional.of(variant));
    }
}
