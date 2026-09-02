package com.tienda.telegram.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tienda.config.MetricsConfig;
import com.tienda.gemini.dto.GeminiChatResult;
import com.tienda.gemini.service.GeminiService;
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
import org.mockito.ArgumentCaptor;
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

    @Mock
    private GeminiService geminiService;

    @Mock
    private ChatSessionService chatSessionService;

    private PurchaseIntentResolver purchaseIntentResolver;

    private SimpleMeterRegistry meterRegistry;

    private TelegramBotService telegramBotService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        purchaseIntentResolver = new PurchaseIntentResolver();
        chatSessionService = new ChatSessionService(productVariantRepository, purchaseIntentResolver);
        telegramBotService = new TelegramBotService(
                productService,
                orderService,
                customerRepository,
                productVariantRepository,
                telegramClientService,
                geminiService,
                chatSessionService,
                purchaseIntentResolver,
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
                .color("Corsa / Aveo 1.4")
                .stock(10)
                .isActive(true)
                .build();

        when(productService.getAllActiveCategories()).thenReturn(List.of(category));
        when(productService.getActiveProductsByCategory(1L)).thenReturn(List.of(product));
        when(productService.getActiveVariantsByProductId(10L)).thenReturn(List.of(variant));
        when(telegramClientService.buildCatalogKeyboard(List.of("FRN-CHE-001")))
                .thenReturn(Map.of("inline_keyboard", List.of()));

        String response = telegramBotService.processUpdate(update);

        assertTrue(response.contains("Catálogo de Autorepuestos"));
        assertTrue(response.contains("Pastillas de Freno Cerámicas"));
        assertTrue(response.contains("FRN-CHE-001"));
        assertTrue(response.contains("Corsa / Aveo 1.4"));
        verify(productService).getAllActiveCategories();
        verify(productService).getActiveProductsByCategory(1L);
        verify(productService).getActiveVariantsByProductId(10L);
        verify(telegramClientService).sendMessageWithInlineKeyboard(eq(chatId), eq(response), any());
        verify(telegramClientService, never()).sendMessage(eq(chatId), eq(response));
        assertEquals(1.0, meterRegistry.counter(
                MetricsConfig.TELEGRAM_COMMANDS_METRIC, "command", "catalogo").count());
    }

    @Test
    void processUpdate_comprarCommand_withoutQuantity_showsQuantitySelector() {
        Long chatId = 111222333L;
        TelegramUpdateDTO update = buildUpdate(chatId, "/comprar FRN-CHE-001", "Taller");

        Customer customer = buildCustomer(chatId);
        ProductVariant variant = buildVariant("FRN-CHE-001", 5, new BigDecimal("85000"));
        Map<String, Object> keyboard = Map.of("inline_keyboard", List.of());

        when(customerRepository.findByTelegramChatId(chatId)).thenReturn(Optional.of(customer));
        when(productVariantRepository.findBySku("FRN-CHE-001")).thenReturn(Optional.of(variant));
        when(telegramClientService.buildQuantitySelectorKeyboard("FRN-CHE-001")).thenReturn(keyboard);

        String response = telegramBotService.processUpdate(update);

        assertTrue(response.contains("Selecciona la cantidad"));
        assertTrue(response.contains("Pastillas de Freno Cerámicas"));
        assertTrue(response.contains("Stock disponible: 5 unidades"));
        verify(telegramClientService).buildQuantitySelectorKeyboard("FRN-CHE-001");
        verify(telegramClientService).sendMessageWithInlineKeyboard(eq(chatId), eq(response), eq(keyboard));
        verify(telegramClientService, never()).buildOrderConfirmationKeyboard(any(), anyInt());
        verify(orderService, never()).createOrder(any(), any());
    }

    @Test
    void processUpdate_selectQtyCallback_validQuantity_showsOrderSummaryWithConfirmation() {
        Long chatId = 111222333L;
        Long messageId = 55L;

        ProductVariant variant = buildVariant("FRN-CHE-001", 5, new BigDecimal("85000"));
        Map<String, Object> keyboard = Map.of("inline_keyboard", List.of());

        TelegramUpdateDTO update = buildCallbackUpdate(
                chatId, messageId, "callback-qty", "SELECT_QTY:FRN-CHE-001:4");

        when(productVariantRepository.findBySku("FRN-CHE-001")).thenReturn(Optional.of(variant));
        when(telegramClientService.buildOrderConfirmationKeyboard("FRN-CHE-001", 4)).thenReturn(keyboard);

        String response = telegramBotService.processUpdate(update);

        assertTrue(response.contains("Resumen de tu Pedido"));
        assertTrue(response.contains("Cantidad:* 4"));
        assertTrue(response.contains("$340.000"));
        verify(telegramClientService).editMessageTextWithInlineKeyboard(eq(chatId), eq(messageId), eq(response), eq(keyboard));
        verify(orderService, never()).createOrder(any(), any());
    }

    @Test
    void processUpdate_selectQtyCallback_quantityExceedsStock_editsMessageWithoutConfirmation() {
        Long chatId = 111222333L;
        Long messageId = 56L;

        ProductVariant variant = buildVariant("FRN-CHE-001", 3, new BigDecimal("85000"));

        TelegramUpdateDTO update = buildCallbackUpdate(
                chatId, messageId, "callback-qty", "SELECT_QTY:FRN-CHE-001:8");

        when(productVariantRepository.findBySku("FRN-CHE-001")).thenReturn(Optional.of(variant));

        String response = telegramBotService.processUpdate(update);

        assertTrue(response.contains("Stock insuficiente"));
        assertTrue(response.contains("8"));
        assertTrue(response.contains("3"));
        verify(telegramClientService).editMessageText(eq(chatId), eq(messageId), eq(response));
        verify(telegramClientService, never()).editMessageTextWithInlineKeyboard(any(), any(), any(), any());
        verify(orderService, never()).createOrder(any(), any());
    }

    @Test
    void processUpdate_comprarCommand_withQuantity_calculatesTotalAndSendsConfirmation() {
        Long chatId = 111222333L;
        TelegramUpdateDTO update = buildUpdate(chatId, "/comprar FRN-CHE-001 3", "Taller");

        Customer customer = buildCustomer(chatId);
        ProductVariant variant = buildVariant("FRN-CHE-001", 5, new BigDecimal("85000"));
        Map<String, Object> keyboard = Map.of("inline_keyboard", List.of());

        when(customerRepository.findByTelegramChatId(chatId)).thenReturn(Optional.of(customer));
        when(productVariantRepository.findBySku("FRN-CHE-001")).thenReturn(Optional.of(variant));
        when(telegramClientService.buildOrderConfirmationKeyboard("FRN-CHE-001", 3)).thenReturn(keyboard);

        String response = telegramBotService.processUpdate(update);

        assertTrue(response.contains("Cantidad:* 3"));
        assertTrue(response.contains("$255.000"));
        verify(telegramClientService).buildOrderConfirmationKeyboard("FRN-CHE-001", 3);
        verify(telegramClientService).sendMessageWithInlineKeyboard(eq(chatId), eq(response), eq(keyboard));
        verify(orderService, never()).createOrder(any(), any());
    }

    @Test
    void processUpdate_comprarCommand_quantityExceedsStock_sendsFriendlyMessageWithoutKeyboard() {
        Long chatId = 111222333L;
        TelegramUpdateDTO update = buildUpdate(chatId, "/comprar FRN-CHE-001 10", "Taller");

        Customer customer = buildCustomer(chatId);
        ProductVariant variant = buildVariant("FRN-CHE-001", 5, new BigDecimal("85000"));

        when(customerRepository.findByTelegramChatId(chatId)).thenReturn(Optional.of(customer));
        when(productVariantRepository.findBySku("FRN-CHE-001")).thenReturn(Optional.of(variant));

        String response = telegramBotService.processUpdate(update);

        assertTrue(response.contains("Stock insuficiente"));
        assertTrue(response.contains("10"));
        assertTrue(response.contains("5"));
        assertTrue(response.contains("/comprar FRN-CHE-001 5"));
        verify(telegramClientService, never()).sendMessageWithInlineKeyboard(any(), any(), any());
        verify(telegramClientService).sendMessage(eq(chatId), eq(response));
        verify(orderService, never()).createOrder(any(), any());
    }

    @Test
    void processUpdate_confirmOrderCallback_createsPendingOrderWithRequestedQuantity() {
        Long chatId = 111222333L;
        Long messageId = 77L;

        Customer customer = buildCustomer(chatId);
        ProductVariant variant = buildVariant("FRN-CHE-001", 5, new BigDecimal("85000"));

        OrderDTO createdOrder = OrderDTO.builder()
                .id(99L)
                .status(OrderStatus.PENDING)
                .totalAmount(new BigDecimal("255000"))
                .build();

        TelegramUpdateDTO update = buildCallbackUpdate(
                chatId, messageId, "callback-123", "CONFIRM_ORDER:FRN-CHE-001:3");

        when(customerRepository.findByTelegramChatId(chatId)).thenReturn(Optional.of(customer));
        when(productVariantRepository.findBySku("FRN-CHE-001")).thenReturn(Optional.of(variant));
        when(orderService.createOrder(eq(5L), any())).thenReturn(createdOrder);

        String response = telegramBotService.processUpdate(update);

        assertTrue(response.contains("Pedido Registrado con Éxito"));
        assertTrue(response.contains("255"));

        ArgumentCaptor<List<OrderItemRequestDTO>> itemsCaptor = ArgumentCaptor.forClass(List.class);
        verify(orderService).createOrder(eq(5L), itemsCaptor.capture());
        assertEquals(1, itemsCaptor.getValue().size());
        assertEquals(3, itemsCaptor.getValue().get(0).getQuantity());
        assertEquals(1L, itemsCaptor.getValue().get(0).getProductVariantId());
        verify(telegramClientService).editMessageText(eq(chatId), eq(messageId), eq(response));
    }

    @Test
    void processUpdate_confirmOrderCallback_withoutQuantityInPayload_defaultsToOne() {
        Long chatId = 111222333L;
        Long messageId = 77L;

        Customer customer = buildCustomer(chatId);
        ProductVariant variant = buildVariant("FRN-CHE-001", 5, new BigDecimal("85000"));

        OrderDTO createdOrder = OrderDTO.builder()
                .id(99L)
                .status(OrderStatus.PENDING)
                .totalAmount(new BigDecimal("85000"))
                .build();

        TelegramUpdateDTO update = buildCallbackUpdate(
                chatId, messageId, "callback-123", "CONFIRM_ORDER:FRN-CHE-001");

        when(customerRepository.findByTelegramChatId(chatId)).thenReturn(Optional.of(customer));
        when(productVariantRepository.findBySku("FRN-CHE-001")).thenReturn(Optional.of(variant));
        when(orderService.createOrder(eq(5L), any())).thenReturn(createdOrder);

        telegramBotService.processUpdate(update);

        ArgumentCaptor<List<OrderItemRequestDTO>> itemsCaptor = ArgumentCaptor.forClass(List.class);
        verify(orderService).createOrder(eq(5L), itemsCaptor.capture());
        assertEquals(1, itemsCaptor.getValue().get(0).getQuantity());
    }

    @Test
    void processUpdate_cancelOrderCallback_editsMessageWithoutCreatingOrder() {
        Long chatId = 111222333L;
        Long messageId = 88L;

        TelegramUpdateDTO update = buildCallbackUpdate(chatId, messageId, "callback-456", "CANCEL_ORDER");

        String response = telegramBotService.processUpdate(update);

        assertTrue(response.contains("Pedido cancelado"));
        verify(telegramClientService).answerCallbackQuery("callback-456");
        verify(telegramClientService).editMessageText(eq(chatId), eq(messageId), eq(response));
        verify(orderService, never()).createOrder(any(), any());
    }

    @Test
    void processUpdate_freeTextMessage_invokesGeminiAndSendsResponse() {
        Long chatId = 777888999L;
        TelegramUpdateDTO update = buildUpdate(chatId, "¿Cuánto cuesta FRN-CHE-001?", "Cliente");

        Customer customer = buildCustomer(chatId);
        when(customerRepository.findByTelegramChatId(chatId)).thenReturn(Optional.of(customer));
        when(geminiService.chat("¿Cuánto cuesta FRN-CHE-001?", customer.getId(), ""))
                .thenReturn(new GeminiChatResult(
                        "El repuesto FRN-CHE-001 cuesta $85.000 COP y hay 15 unidades disponibles.",
                        List.of("FRN-CHE-001")));
        when(telegramClientService.buildSuggestedSkusKeyboard(List.of("FRN-CHE-001")))
                .thenReturn(Map.of("inline_keyboard", List.of()));

        String response = telegramBotService.processUpdate(update);

        assertTrue(response.contains("$85.000 COP"));
        verify(geminiService).chat("¿Cuánto cuesta FRN-CHE-001?", customer.getId(), "");
        verify(telegramClientService).sendMessageWithInlineKeyboard(eq(chatId), eq(response), any());
    }

    @Test
    void processUpdate_catalogIntentWithoutSlash_routesToCatalogWithoutGemini() {
        Long chatId = 777888999L;
        TelegramUpdateDTO update = buildUpdate(chatId, "catalogo, déjame verlo", "Cliente");

        stubCatalogData();

        String response = telegramBotService.processUpdate(update);

        assertTrue(response.contains("Catálogo de Autorepuestos"));
        verify(geminiService, never()).chat(any(), any());
        verify(productService).getAllActiveCategories();
    }

    @Test
    void processUpdate_queVendesIntent_routesToCatalogWithoutGemini() {
        Long chatId = 777888999L;
        TelegramUpdateDTO update = buildUpdate(chatId, "quiero saber qué vendes", "Cliente");

        stubCatalogData();

        String response = telegramBotService.processUpdate(update);

        assertTrue(response.contains("Catálogo de Autorepuestos"));
        verify(geminiService, never()).chat(any(), any());
    }

    @Test
    void processUpdate_greetingIntent_routesToWelcomeWithoutGemini() {
        Long chatId = 777888999L;
        TelegramUpdateDTO update = buildUpdate(chatId, "hola", "Cliente");

        Customer customer = buildCustomer(chatId);
        when(customerRepository.findByTelegramChatId(chatId)).thenReturn(Optional.of(customer));

        String response = telegramBotService.processUpdate(update);

        assertTrue(response.contains("Autorepuestos Demo"));
        assertTrue(response.contains("/catalogo"));
        verify(geminiService, never()).chat(any(), any());
        verify(customerRepository).findByTelegramChatId(chatId);
    }

    @Test
    void resolveLocalIntent_detectsNormalizedKeywords() {
        assertEquals(TelegramBotService.LocalIntent.CATALOG,
                telegramBotService.resolveLocalIntent(telegramBotService.normalizeForIntent("catálogo")));
        assertEquals(TelegramBotService.LocalIntent.GREETING,
                telegramBotService.resolveLocalIntent(telegramBotService.normalizeForIntent("Buenas!")));
        assertEquals(TelegramBotService.LocalIntent.NONE,
                telegramBotService.resolveLocalIntent(telegramBotService.normalizeForIntent("¿Cuánto cuesta FRN-CHE-001?")));
    }

    private void stubCatalogData() {
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
                .color("Corsa / Aveo 1.4")
                .stock(10)
                .isActive(true)
                .build();

        when(productService.getAllActiveCategories()).thenReturn(List.of(category));
        when(productService.getActiveProductsByCategory(1L)).thenReturn(List.of(product));
        when(productService.getActiveVariantsByProductId(10L)).thenReturn(List.of(variant));
        when(telegramClientService.buildCatalogKeyboard(List.of("FRN-CHE-001")))
                .thenReturn(Map.of("inline_keyboard", List.of()));
    }

    @Test
    void processUpdate_catalogoCommand_excludesZeroStockVariants() {
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

        ProductVariantDTO inStock = ProductVariantDTO.builder()
                .id(100L)
                .productId(10L)
                .sku("FRN-CHE-001")
                .stock(10)
                .isActive(true)
                .build();

        ProductVariantDTO outOfStock = ProductVariantDTO.builder()
                .id(101L)
                .productId(10L)
                .sku("FRN-REN-002")
                .stock(0)
                .isActive(true)
                .build();

        when(productService.getAllActiveCategories()).thenReturn(List.of(category));
        when(productService.getActiveProductsByCategory(1L)).thenReturn(List.of(product));
        when(productService.getActiveVariantsByProductId(10L)).thenReturn(List.of(inStock, outOfStock));
        when(telegramClientService.buildCatalogKeyboard(List.of("FRN-CHE-001")))
                .thenReturn(Map.of("inline_keyboard", List.of()));

        String response = telegramBotService.processUpdate(update);

        assertTrue(response.contains("FRN-CHE-001"));
        assertTrue(response.contains("$85.000"));
        assertTrue(!response.contains("FRN-REN-002"));
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

    @Test
    void processUpdate_contextualPurchaseConfirmation_showsMultiItemSummaryWithoutGemini() {
        Long chatId = 777888999L;
        Customer customer = buildCustomer(chatId);

        ProductVariant pastillas = buildVariant("FRN-TOY-003", 6, new BigDecimal("145000"));
        ProductVariant amortiguadores = ProductVariant.builder()
                .id(2L)
                .product(Product.builder()
                        .id(2L)
                        .name("Amortiguadores a Gas Nitrógeno")
                        .basePrice(new BigDecimal("180000"))
                        .build())
                .sku("SUS-CHE-021")
                .color("Traseros Corsa Evolution")
                .stock(8)
                .isActive(true)
                .build();

        when(customerRepository.findByTelegramChatId(chatId)).thenReturn(Optional.of(customer));
        when(productVariantRepository.findBySku("FRN-TOY-003")).thenReturn(Optional.of(pastillas));
        when(productVariantRepository.findBySku("SUS-CHE-021")).thenReturn(Optional.of(amortiguadores));
        when(telegramClientService.buildMultiOrderConfirmationKeyboard(any()))
                .thenReturn(Map.of("inline_keyboard", List.of()));

        chatSessionService.recordShownSkus(chatId, List.of("FRN-TOY-003", "SUS-CHE-021"));

        TelegramUpdateDTO update = buildUpdate(
                chatId, "si ambos que me mostraste, quiero los 2, los comprare", "Joel");

        String response = telegramBotService.processUpdate(update);

        assertTrue(response.contains("Resumen de tu Pedido"));
        assertTrue(response.contains("FRN-TOY-003"));
        assertTrue(response.contains("SUS-CHE-021"));
        verify(geminiService, never()).chat(any(), any(), any());
        verify(telegramClientService).sendMessageWithInlineKeyboard(eq(chatId), eq(response), any());
    }

    @Test
    void processUpdate_confirmMultiOrderCallback_createsOrderWithMultipleItems() {
        Long chatId = 111222333L;
        Long messageId = 90L;

        Customer customer = buildCustomer(chatId);
        ProductVariant pastillas = buildVariant("FRN-TOY-003", 6, new BigDecimal("145000"));
        ProductVariant amortiguadores = ProductVariant.builder()
                .id(2L)
                .product(Product.builder()
                        .id(2L)
                        .name("Amortiguadores a Gas Nitrógeno")
                        .basePrice(new BigDecimal("180000"))
                        .build())
                .sku("SUS-CHE-021")
                .stock(8)
                .isActive(true)
                .build();

        OrderDTO createdOrder = OrderDTO.builder()
                .id(150L)
                .status(OrderStatus.PENDING)
                .totalAmount(new BigDecimal("325000"))
                .build();

        TelegramUpdateDTO update = buildCallbackUpdate(
                chatId, messageId, "callback-multi", "CONFIRM_MULTI_ORDER:FRN-TOY-003:1,SUS-CHE-021:1");

        when(customerRepository.findByTelegramChatId(chatId)).thenReturn(Optional.of(customer));
        when(productVariantRepository.findBySku("FRN-TOY-003")).thenReturn(Optional.of(pastillas));
        when(productVariantRepository.findBySku("SUS-CHE-021")).thenReturn(Optional.of(amortiguadores));
        when(orderService.createOrder(eq(5L), any())).thenReturn(createdOrder);

        String response = telegramBotService.processUpdate(update);

        assertTrue(response.contains("Pedido Registrado con Éxito"));

        ArgumentCaptor<List<OrderItemRequestDTO>> itemsCaptor = ArgumentCaptor.forClass(List.class);
        verify(orderService).createOrder(eq(5L), itemsCaptor.capture());
        assertEquals(2, itemsCaptor.getValue().size());
        verify(telegramClientService).editMessageText(eq(chatId), eq(messageId), eq(response));
    }

    @Test
    void processUpdate_multiProductFreeText_sendsProcessingNoticeAndGeminiResponse() {
        Long chatId = 777888999L;
        TelegramUpdateDTO update = buildUpdate(
                chatId,
                "pastillas Fortuner, amortiguadores traseros Corsa y kit Logan",
                "Cliente");

        Customer customer = buildCustomer(chatId);
        when(customerRepository.findByTelegramChatId(chatId)).thenReturn(Optional.of(customer));
        when(geminiService.chat(
                        eq("pastillas Fortuner, amortiguadores traseros Corsa y kit Logan"),
                        eq(customer.getId()),
                        any()))
                .thenReturn(new GeminiChatResult(
                        "",
                        List.of("FRN-TOY-003", "SUS-CHE-021", "LUB-REN-011"),
                        List.of()));
        when(telegramClientService.buildSuggestedSkusKeyboard(any()))
                .thenReturn(Map.of("inline_keyboard", List.of()));

        String response = telegramBotService.processUpdate(update);

        assertTrue(response.contains("Encontré estos repuestos"));
        verify(telegramClientService).sendMessage(
                eq(chatId),
                eq("⏳ Estoy buscando los repuestos que necesitas, un momento por favor..."));
        verify(telegramClientService).sendMessageWithInlineKeyboard(eq(chatId), eq(response), any());
    }

    @Test
    void processUpdate_geminiPendingOrderLines_showsConsolidatedSummary() {
        Long chatId = 777888999L;
        TelegramUpdateDTO update = buildUpdate(
                chatId,
                "quiero pastillas Fortuner y amortiguadores traseros Corsa",
                "Cliente");

        Customer customer = buildCustomer(chatId);
        ProductVariant pastillas = buildVariant("FRN-TOY-003", 6, new BigDecimal("145000"));
        ProductVariant amortiguadores = ProductVariant.builder()
                .id(2L)
                .product(Product.builder()
                        .id(2L)
                        .name("Amortiguadores a Gas Nitrógeno")
                        .basePrice(new BigDecimal("180000"))
                        .build())
                .sku("SUS-CHE-021")
                .color("Traseros Corsa Evolution")
                .stock(8)
                .isActive(true)
                .build();

        when(customerRepository.findByTelegramChatId(chatId)).thenReturn(Optional.of(customer));
        when(geminiService.chat(any(), eq(customer.getId()), any()))
                .thenReturn(new GeminiChatResult(
                        "Preparé tu pedido con ambos repuestos.",
                        List.of("FRN-TOY-003", "SUS-CHE-021"),
                        List.of(
                                new com.tienda.telegram.dto.PendingOrderLine("FRN-TOY-003", 1),
                                new com.tienda.telegram.dto.PendingOrderLine("SUS-CHE-021", 1))));
        when(productVariantRepository.findBySku("FRN-TOY-003")).thenReturn(Optional.of(pastillas));
        when(productVariantRepository.findBySku("SUS-CHE-021")).thenReturn(Optional.of(amortiguadores));
        when(telegramClientService.buildMultiOrderConfirmationKeyboard(any()))
                .thenReturn(Map.of("inline_keyboard", List.of()));

        String response = telegramBotService.processUpdate(update);

        assertTrue(response.contains("Resumen de tu Pedido"));
        assertTrue(response.contains("FRN-TOY-003"));
        assertTrue(response.contains("SUS-CHE-021"));
        verify(telegramClientService).sendMessageWithInlineKeyboard(eq(chatId), eq(response), any());
    }

    @Test
    void looksLikeMultiProductRequest_detectsCommaSeparatedProducts() {
        assertTrue(telegramBotService.looksLikeMultiProductRequest(
                "pastillas Fortuner, amortiguadores traseros Corsa y kit Logan"));
    }

    private Customer buildCustomer(Long chatId) {
        return Customer.builder()
                .id(5L)
                .telegramChatId(chatId)
                .fullName("Taller")
                .createdAt(LocalDateTime.now())
                .build();
    }

    private ProductVariant buildVariant(String sku, int stock, BigDecimal basePrice) {
        Product product = Product.builder()
                .id(1L)
                .name("Pastillas de Freno Cerámicas")
                .basePrice(basePrice)
                .build();

        return ProductVariant.builder()
                .id(1L)
                .product(product)
                .sku(sku)
                .color("Corsa / Aveo 1.4")
                .stock(stock)
                .isActive(true)
                .build();
    }

    private TelegramUpdateDTO buildCallbackUpdate(
            Long chatId, Long messageId, String callbackId, String callbackData) {
        return TelegramUpdateDTO.builder()
                .updateId(2L)
                .callbackQuery(TelegramCallbackQueryDTO.builder()
                        .id(callbackId)
                        .data(callbackData)
                        .message(TelegramMessageDTO.builder()
                                .messageId(messageId)
                                .chat(TelegramChatDTO.builder()
                                        .id(chatId)
                                        .firstName("Taller")
                                        .build())
                                .build())
                        .build())
                .build();
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
