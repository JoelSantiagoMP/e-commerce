package com.tienda.telegram.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tienda.config.MetricsConfig;
import com.tienda.dto.CategoryDTO;
import com.tienda.dto.OrderDTO;
import com.tienda.dto.OrderItemRequestDTO;
import com.tienda.dto.ProductDTO;
import com.tienda.dto.ProductVariantDTO;
import com.tienda.entity.Customer;
import com.tienda.entity.OrderStatus;
import com.tienda.entity.Product;
import com.tienda.entity.ProductVariant;
import com.tienda.repository.CustomerRepository;
import com.tienda.repository.ProductVariantRepository;
import com.tienda.service.OrderService;
import com.tienda.service.ProductService;
import com.tienda.telegram.dto.TelegramCallbackQueryDTO;
import com.tienda.telegram.dto.TelegramChatDTO;
import com.tienda.telegram.dto.TelegramMessageDTO;
import com.tienda.telegram.dto.TelegramUpdateDTO;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TelegramBotServiceTest {

    @Mock
    private ProductService productService;

    @Mock
    private OrderService orderService;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private ProductVariantRepository productVariantRepository;

    @Mock
    private TelegramClientService telegramClientService;

    private SimpleMeterRegistry meterRegistry;

    private TelegramBotService telegramBotService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        telegramBotService = new TelegramBotService(
                productService,
                orderService,
                customerRepository,
                productVariantRepository,
                telegramClientService,
                meterRegistry
        );
        ReflectionTestUtils.setField(telegramBotService, "botUsername", "Autorepuestosdemo_bot");
    }

    @Test
    void processUpdate_startCommand_createsCustomerAndSendsWelcome() {
        Long chatId = 123456789L;
        TelegramUpdateDTO update = buildUpdate(chatId, "/start", "Joel");

        Customer savedCustomer = Customer.builder()
                .id(1L)
                .telegramChatId(chatId)
                .fullName("Joel")
                .createdAt(LocalDateTime.now())
                .build();

        when(customerRepository.findByTelegramChatId(chatId)).thenReturn(Optional.empty());
        when(customerRepository.save(any(Customer.class))).thenReturn(savedCustomer);

        String response = telegramBotService.processUpdate(update);

        assertTrue(response.contains("Autorepuestos Demo"));
        assertTrue(response.contains("/catalogo"));
        verify(customerRepository).findByTelegramChatId(chatId);
        verify(customerRepository).save(any(Customer.class));
        verify(telegramClientService).sendMessage(eq(chatId), eq(response));
        assertEquals(1.0, meterRegistry.counter(
                MetricsConfig.TELEGRAM_COMMANDS_METRIC, "command", "start").count());
    }

    @Test
    void processUpdate_catalogoCommand_buildsProductListAndSendsMessage() {
        Long chatId = 987654321L;
        TelegramUpdateDTO update = buildUpdate(chatId, "/catalogo", "Test");

        CategoryDTO category = CategoryDTO.builder()
                .id(1L)
                .name("Sistema de Frenos")
                .isActive(true)
                .build();

        ProductDTO product = ProductDTO.builder()
                .id(10L)
                .categoryId(1L)
                .name("Pastillas de Freno Cerámicas")
                .basePrice(new BigDecimal("85000"))
                .isActive(true)
                .build();

        ProductVariantDTO variant = ProductVariantDTO.builder()
                .id(100L)
                .productId(10L)
                .sku("FRN-CHE-001")
                .stock(10)
                .isActive(true)
                .build();

        when(productService.getAllActiveCategories()).thenReturn(List.of(category));
        when(productService.getActiveProductsByCategory(1L)).thenReturn(List.of(product));
        when(productService.getActiveVariantsByProductId(10L)).thenReturn(List.of(variant));

        String response = telegramBotService.processUpdate(update);

        assertTrue(response.contains("Catálogo de Autorepuestos"));
        assertTrue(response.contains("Pastillas de Freno Cerámicas"));
        assertTrue(response.contains("FRN-CHE-001"));
        verify(productService).getAllActiveCategories();
        verify(productService).getActiveProductsByCategory(1L);
        verify(productService).getActiveVariantsByProductId(10L);
        verify(telegramClientService).sendMessage(eq(chatId), eq(response));
        assertEquals(1.0, meterRegistry.counter(
                MetricsConfig.TELEGRAM_COMMANDS_METRIC, "command", "catalogo").count());
    }

    @Test
    void processUpdate_comprarCommand_sendsInteractiveConfirmationWithoutCreatingOrder() {
        Long chatId = 111222333L;
        TelegramUpdateDTO update = buildUpdate(chatId, "/comprar FRN-CHE-001", "Taller");

        Customer customer = Customer.builder()
                .id(5L)
                .telegramChatId(chatId)
                .fullName("Taller")
                .createdAt(LocalDateTime.now())
                .build();

        Product product = Product.builder()
                .id(1L)
                .name("Pastillas de Freno Cerámicas")
                .basePrice(new BigDecimal("85000"))
                .build();
        ProductVariant variant = ProductVariant.builder()
                .id(1L)
                .product(product)
                .sku("FRN-CHE-001")
                .stock(5)
                .isActive(true)
                .build();

        Map<String, Object> keyboard = Map.of("inline_keyboard", List.of());

        when(customerRepository.findByTelegramChatId(chatId)).thenReturn(Optional.of(customer));
        when(productVariantRepository.findBySku("FRN-CHE-001")).thenReturn(Optional.of(variant));
        when(telegramClientService.buildOrderConfirmationKeyboard("FRN-CHE-001")).thenReturn(keyboard);

        String response = telegramBotService.processUpdate(update);

        assertTrue(response.contains("Resumen de tu Pedido"));
        assertTrue(response.contains("Pastillas de Freno Cerámicas"));
        assertTrue(response.contains("FRN-CHE-001"));
        assertTrue(response.contains("¿Deseas confirmar este pedido?"));
        verify(orderService, never()).createOrder(any(), any());
        verify(telegramClientService).sendMessageWithInlineKeyboard(eq(chatId), eq(response), eq(keyboard));
        verify(telegramClientService, never()).sendMessage(eq(chatId), any());
    }

    @Test
    void processUpdate_confirmOrderCallback_createsPendingOrderAndEditsMessage() {
        Long chatId = 111222333L;
        Long messageId = 77L;

        Customer customer = Customer.builder()
                .id(5L)
                .telegramChatId(chatId)
                .fullName("Taller")
                .createdAt(LocalDateTime.now())
                .build();

        Product product = Product.builder()
                .id(1L)
                .name("Pastillas de Freno Cerámicas")
                .basePrice(new BigDecimal("85000"))
                .build();
        ProductVariant variant = ProductVariant.builder()
                .id(1L)
                .product(product)
                .sku("FRN-CHE-001")
                .stock(5)
                .isActive(true)
                .build();

        OrderDTO createdOrder = OrderDTO.builder()
                .id(99L)
                .status(OrderStatus.PENDING)
                .totalAmount(new BigDecimal("85000"))
                .build();

        TelegramUpdateDTO update = TelegramUpdateDTO.builder()
                .updateId(2L)
                .callbackQuery(TelegramCallbackQueryDTO.builder()
                        .id("callback-123")
                        .data("CONFIRM_ORDER:FRN-CHE-001")
                        .message(TelegramMessageDTO.builder()
                                .messageId(messageId)
                                .chat(TelegramChatDTO.builder()
                                        .id(chatId)
                                        .firstName("Taller")
                                        .build())
                                .build())
                        .build())
                .build();

        when(customerRepository.findByTelegramChatId(chatId)).thenReturn(Optional.of(customer));
        when(productVariantRepository.findBySku("FRN-CHE-001")).thenReturn(Optional.of(variant));
        when(orderService.createOrder(eq(5L), any())).thenReturn(createdOrder);

        String response = telegramBotService.processUpdate(update);

        assertTrue(response.contains("Pedido Registrado con Éxito"));
        assertTrue(response.contains("Orden #"));
        assertTrue(response.contains("99"));
        assertTrue(response.contains("PENDING"));
        assertTrue(response.contains("3001234567"));
        assertTrue(response.contains("https://pago.autorepuestos.com/order-99"));
        verify(telegramClientService).answerCallbackQuery("callback-123");
        verify(orderService).createOrder(eq(5L), any(List.class));
        verify(telegramClientService).editMessageText(eq(chatId), eq(messageId), eq(response));
        verify(telegramClientService, never()).sendMessage(any(), any());
    }

    @Test
    void processUpdate_cancelOrderCallback_editsMessageWithoutCreatingOrder() {
        Long chatId = 111222333L;
        Long messageId = 88L;

        TelegramUpdateDTO update = TelegramUpdateDTO.builder()
                .updateId(3L)
                .callbackQuery(TelegramCallbackQueryDTO.builder()
                        .id("callback-456")
                        .data("CANCEL_ORDER")
                        .message(TelegramMessageDTO.builder()
                                .messageId(messageId)
                                .chat(TelegramChatDTO.builder()
                                        .id(chatId)
                                        .firstName("Taller")
                                        .build())
                                .build())
                        .build())
                .build();

        String response = telegramBotService.processUpdate(update);

        assertTrue(response.contains("Pedido cancelado"));
        verify(telegramClientService).answerCallbackQuery("callback-456");
        verify(telegramClientService).editMessageText(eq(chatId), eq(messageId), eq(response));
        verify(orderService, never()).createOrder(any(), any());
    }

    @Test
    void processUpdate_unknownCommand_incrementsUnknownMetricAndSendsDefaultMessage() {
        Long chatId = 555555555L;
        TelegramUpdateDTO update = buildUpdate(chatId, "/ayuda", "User");

        String response = telegramBotService.processUpdate(update);

        assertTrue(response.contains("Comando no reconocido"));
        assertTrue(response.contains("/catalogo"));
        verify(telegramClientService).sendMessage(eq(chatId), eq(response));
        verify(customerRepository, never()).findByTelegramChatId(any());
        verify(productService, never()).getAllActiveCategories();
        assertEquals(1.0, meterRegistry.counter(
                MetricsConfig.TELEGRAM_COMMANDS_METRIC, "command", "unknown").count());
    }

    private TelegramUpdateDTO buildUpdate(Long chatId, String text, String firstName) {
        return TelegramUpdateDTO.builder()
                .updateId(1L)
                .message(TelegramMessageDTO.builder()
                        .messageId(42L)
                        .chat(TelegramChatDTO.builder()
                                .id(chatId)
                                .firstName(firstName)
                                .username("test_user")
                                .build())
                        .text(text)
                        .build())
                .build();
    }
}
