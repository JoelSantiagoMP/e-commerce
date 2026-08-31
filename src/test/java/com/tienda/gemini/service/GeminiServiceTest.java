package com.tienda.gemini.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tienda.dto.CategoryDTO;
import com.tienda.dto.OrderDTO;
import com.tienda.dto.OrderItemRequestDTO;
import com.tienda.dto.ProductDTO;
import com.tienda.dto.ProductVariantDTO;
import com.tienda.entity.OrderStatus;
import com.tienda.entity.Product;
import com.tienda.entity.ProductVariant;
import com.tienda.exception.InsufficientStockException;
import com.tienda.gemini.config.GeminiProperties;
import com.tienda.repository.ProductVariantRepository;
import com.tienda.service.OrderService;
import com.tienda.service.ProductService;
import java.math.BigDecimal;
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
class GeminiServiceTest {

    @Mock
    private RestClient geminiRestClient;

    @Mock
    private ProductVariantRepository productVariantRepository;

    @Mock
    private OrderService orderService;

    @Mock
    private ProductService productService;

    private GeminiProperties geminiProperties;
    private GeminiService geminiService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        geminiProperties = new GeminiProperties();
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
    void executeFunction_consultarStock_returnsStockDetails() {
        Product product = Product.builder()
                .name("Pastillas de Freno")
                .basePrice(new BigDecimal("85000"))
                .build();

        ProductVariant variant = ProductVariant.builder()
                .sku("FRN-CHE-001")
                .stock(12)
                .size("STD")
                .color("Negro")
                .isActive(true)
                .product(product)
                .build();

        when(productVariantRepository.findBySku("FRN-CHE-001")).thenReturn(Optional.of(variant));

        ObjectNode args = objectMapper.createObjectNode().put("sku", "FRN-CHE-001");
        Map<String, Object> result = geminiService.executeFunction("consultarStock", args, 1L);

        assertEquals("FRN-CHE-001", result.get("sku"));
        assertEquals("Pastillas de Freno", result.get("productName"));
        assertEquals(12, result.get("stock"));
        assertEquals(new BigDecimal("85000"), result.get("price"));
        assertEquals(true, result.get("available"));
    }

    @Test
    void executeFunction_consultarStock_returnsErrorWhenSkuNotFound() {
        when(productVariantRepository.findBySku("UNKNOWN")).thenReturn(Optional.empty());

        ObjectNode args = objectMapper.createObjectNode().put("sku", "UNKNOWN");
        Map<String, Object> result = geminiService.executeFunction("consultarStock", args, 1L);

        assertEquals(true, result.get("error"));
        assertTrue(result.get("message").toString().contains("UNKNOWN"));
    }

    @Test
    void executeFunction_crearOrden_createsOrderThroughOrderService() {
        Product product = Product.builder()
                .name("Pastillas de Freno")
                .basePrice(new BigDecimal("85000"))
                .build();

        ProductVariant variant = ProductVariant.builder()
                .id(10L)
                .sku("FRN-CHE-001")
                .stock(5)
                .isActive(true)
                .product(product)
                .build();

        OrderDTO createdOrder = OrderDTO.builder()
                .id(99L)
                .status(OrderStatus.PENDING)
                .totalAmount(new BigDecimal("170000"))
                .build();

        when(productVariantRepository.findBySku("FRN-CHE-001")).thenReturn(Optional.of(variant));
        when(orderService.createOrder(eq(7L), any())).thenReturn(createdOrder);

        ObjectNode args = objectMapper.createObjectNode()
                .put("sku", "FRN-CHE-001")
                .put("cantidad", 2);

        Map<String, Object> result = geminiService.executeFunction("crearOrden", args, 7L);

        ArgumentCaptor<List<OrderItemRequestDTO>> itemsCaptor = ArgumentCaptor.forClass(List.class);
        verify(orderService).createOrder(eq(7L), itemsCaptor.capture());

        assertEquals(10L, itemsCaptor.getValue().get(0).getProductVariantId());
        assertEquals(2, itemsCaptor.getValue().get(0).getQuantity());
        assertEquals(99L, result.get("orderId"));
        assertEquals("PENDING", result.get("status"));
        assertTrue(result.get("message").toString().contains("99"));
    }

    @Test
    void executeFunction_crearOrden_returnsErrorWhenInsufficientStock() {
        ProductVariant variant = ProductVariant.builder()
                .id(10L)
                .sku("FRN-CHE-001")
                .stock(1)
                .isActive(true)
                .product(Product.builder().name("Pastillas").basePrice(new BigDecimal("85000")).build())
                .build();

        when(productVariantRepository.findBySku("FRN-CHE-001")).thenReturn(Optional.of(variant));

        ObjectNode args = objectMapper.createObjectNode()
                .put("sku", "FRN-CHE-001")
                .put("cantidad", 5);

        Map<String, Object> result = geminiService.executeFunction("crearOrden", args, 7L);

        assertEquals(true, result.get("error"));
        assertEquals(true, result.get("insufficientStock"));
        assertTrue(result.get("message").toString().contains("Stock insuficiente"));
    }

    @Test
    void executeFunction_listarCatalogo_excludesZeroStockVariants() {
        CategoryDTO category = CategoryDTO.builder().id(1L).name("Frenos").isActive(true).build();
        ProductDTO product = ProductDTO.builder()
                .id(10L)
                .categoryId(1L)
                .name("Pastillas")
                .basePrice(new BigDecimal("85000"))
                .isActive(true)
                .build();
        ProductVariantDTO inStock = ProductVariantDTO.builder()
                .sku("FRN-CHE-001")
                .stock(5)
                .isActive(true)
                .build();
        ProductVariantDTO outOfStock = ProductVariantDTO.builder()
                .sku("FRN-REN-002")
                .stock(0)
                .isActive(true)
                .build();

        when(productService.getAllActiveCategories()).thenReturn(List.of(category));
        when(productService.getActiveProductsByCategory(1L)).thenReturn(List.of(product));
        when(productService.getActiveVariantsByProductId(10L)).thenReturn(List.of(inStock, outOfStock));

        Map<String, Object> result = geminiService.executeFunction("listarCatalogo", objectMapper.createObjectNode(), 1L);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        assertEquals(1, items.size());
        assertEquals("FRN-CHE-001", items.get(0).get("sku"));
        assertEquals(1, result.get("totalAvailable"));
    }

    @Test
    void executeFunction_crearOrden_usesDefaultQuantityWhenMissing() {
        ProductVariant variant = ProductVariant.builder()
                .id(10L)
                .sku("FRN-CHE-001")
                .stock(5)
                .isActive(true)
                .product(Product.builder().name("Pastillas").basePrice(new BigDecimal("85000")).build())
                .build();

        OrderDTO createdOrder = OrderDTO.builder()
                .id(101L)
                .status(OrderStatus.PENDING)
                .totalAmount(new BigDecimal("85000"))
                .build();

        when(productVariantRepository.findBySku("FRN-CHE-001")).thenReturn(Optional.of(variant));
        when(orderService.createOrder(eq(7L), any())).thenReturn(createdOrder);

        ObjectNode args = objectMapper.createObjectNode().put("sku", "FRN-CHE-001");
        Map<String, Object> result = geminiService.executeFunction("crearOrden", args, 7L);

        ArgumentCaptor<List<OrderItemRequestDTO>> itemsCaptor = ArgumentCaptor.forClass(List.class);
        verify(orderService).createOrder(eq(7L), itemsCaptor.capture());
        assertEquals(1, itemsCaptor.getValue().get(0).getQuantity());
        assertEquals(101L, result.get("orderId"));
    }

    @Test
    void executeFunction_consultarStock_returnsFriendlyErrorWhenRepositoryFails() {
        when(productVariantRepository.findBySku("FRN-CHE-001"))
                .thenThrow(new RuntimeException("DB connection failed"));

        ObjectNode args = objectMapper.createObjectNode().put("sku", "FRN-CHE-001");
        Map<String, Object> result = geminiService.executeFunction("consultarStock", args, 1L);

        assertEquals(true, result.get("error"));
        assertTrue(result.get("message").toString().contains("No pude verificar el stock"));
    }

    @Test
    void executeFunction_consultarStock_returnsErrorWhenSkuMissing() {
        ObjectNode args = objectMapper.createObjectNode();
        Map<String, Object> result = geminiService.executeFunction("consultarStock", args, 1L);

        assertEquals(true, result.get("error"));
        assertTrue(result.get("message").toString().contains("sku"));
    }

    @Test
    void executeFunction_unknownFunction_returnsErrorPayload() {
        ObjectNode args = objectMapper.createObjectNode();
        Map<String, Object> result = geminiService.executeFunction("funcionDesconocida", args, 1L);

        assertEquals(true, result.get("error"));
        assertTrue(result.get("message").toString().contains("funcionDesconocida"));
    }
}
