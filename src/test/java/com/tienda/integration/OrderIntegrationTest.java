package com.tienda.integration;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tienda.dto.OrderDTO;
import com.tienda.entity.Customer;
import com.tienda.entity.OrderStatus;
import com.tienda.entity.ProductVariant;
import com.tienda.repository.CustomerRepository;
import com.tienda.repository.OrderRepository;
import com.tienda.repository.ProductVariantRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("test")
class OrderIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProductVariantRepository productVariantRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private CustomerRepository customerRepository;

    private Long customerId;
    private Long variantId;
    private int initialStock;

    @BeforeEach
    void setUp() {
        Customer customer = customerRepository.save(Customer.builder()
                .fullName("Cliente Integración")
                .telegramChatId(System.currentTimeMillis())
                .address("Bucaramanga")
                .createdAt(LocalDateTime.now())
                .build());
        customerId = customer.getId();

        ProductVariant variant = productVariantRepository.findBySku("CAM-BAS-S-BLK")
                .orElseGet(() -> productVariantRepository.findAll().stream()
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException(
                                "No hay variantes en la BD. Ejecute schema.sql y data.sql.")));

        variantId = variant.getId();
        initialStock = variant.getStock();
    }

    @Test
    void testCreateOrderSuccess() throws Exception {
        String requestBody = """
                [{"productVariantId": %d, "quantity": 2}]
                """.formatted(variantId);

        MvcResult result = mockMvc.perform(post("/api/v1/orders")
                        .param("customerId", customerId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.customerId").value(customerId))
                .andReturn();

        OrderDTO createdOrder = objectMapper.readValue(
                result.getResponse().getContentAsString(), OrderDTO.class);

        ProductVariant updatedVariant = productVariantRepository.findById(variantId).orElseThrow();
        assertEquals(initialStock - 2, updatedVariant.getStock());

        assertEquals(OrderStatus.PENDING,
                orderRepository.findById(createdOrder.getId()).orElseThrow().getStatus());
    }

    @Test
    void testCreateOrderInsufficientStock() throws Exception {
        String requestBody = """
                [{"productVariantId": %d, "quantity": 999}]
                """.formatted(variantId);

        mockMvc.perform(post("/api/v1/orders")
                        .param("customerId", customerId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message", containsString("Stock insuficiente")))
                .andExpect(jsonPath("$.timestamp").exists());

        ProductVariant unchangedVariant = productVariantRepository.findById(variantId).orElseThrow();
        assertEquals(initialStock, unchangedVariant.getStock());
    }

    @Test
    void testCancelOrderRestoresStock() throws Exception {
        String requestBody = """
                [{"productVariantId": %d, "quantity": 2}]
                """.formatted(variantId);

        MvcResult createResult = mockMvc.perform(post("/api/v1/orders")
                        .param("customerId", customerId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andReturn();

        OrderDTO createdOrder = objectMapper.readValue(
                createResult.getResponse().getContentAsString(), OrderDTO.class);

        ProductVariant afterOrder = productVariantRepository.findById(variantId).orElseThrow();
        assertEquals(initialStock - 2, afterOrder.getStock());

        mockMvc.perform(patch("/api/v1/orders/{orderId}/status", createdOrder.getId())
                        .param("status", "CANCELLED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        ProductVariant afterCancel = productVariantRepository.findById(variantId).orElseThrow();
        assertEquals(initialStock, afterCancel.getStock());
    }
}
