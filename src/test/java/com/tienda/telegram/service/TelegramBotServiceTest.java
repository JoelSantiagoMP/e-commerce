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
import com.tienda.dto.ProductDTO;
import com.tienda.dto.ProductVariantDTO;
import com.tienda.entity.Customer;
import com.tienda.repository.CustomerRepository;
import com.tienda.service.OrderService;
import com.tienda.service.ProductService;
import com.tienda.telegram.dto.TelegramChatDTO;
import com.tienda.telegram.dto.TelegramMessageDTO;
import com.tienda.telegram.dto.TelegramUpdateDTO;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TelegramBotServiceTest {

    @Mock
    private ProductService productService;

    @Mock
    private OrderService orderService;

    @Mock
    private CustomerRepository customerRepository;

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
                telegramClientService,
                meterRegistry
        );
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

        assertTrue(response.contains("Bienvenido"));
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
                .name("Ropa Deportiva")
                .isActive(true)
                .build();

        ProductDTO product = ProductDTO.builder()
                .id(10L)
                .categoryId(1L)
                .name("Camiseta Pro")
                .basePrice(new BigDecimal("29.99"))
                .isActive(true)
                .build();

        ProductVariantDTO variant = ProductVariantDTO.builder()
                .id(100L)
                .productId(10L)
                .sku("TSHIRT-BLK-M")
                .stock(10)
                .isActive(true)
                .build();

        when(productService.getAllActiveCategories()).thenReturn(List.of(category));
        when(productService.getActiveProductsByCategory(1L)).thenReturn(List.of(product));
        when(productService.getActiveVariantsByProductId(10L)).thenReturn(List.of(variant));

        String response = telegramBotService.processUpdate(update);

        assertTrue(response.contains("Catálogo de productos"));
        assertTrue(response.contains("Camiseta Pro"));
        assertTrue(response.contains("TSHIRT-BLK-M"));
        verify(productService).getAllActiveCategories();
        verify(productService).getActiveProductsByCategory(1L);
        verify(productService).getActiveVariantsByProductId(10L);
        verify(telegramClientService).sendMessage(eq(chatId), eq(response));
        assertEquals(1.0, meterRegistry.counter(
                MetricsConfig.TELEGRAM_COMMANDS_METRIC, "command", "catalogo").count());
    }

    @Test
    void processUpdate_unknownCommand_incrementsUnknownMetricAndSendsDefaultMessage() {
        Long chatId = 555555555L;
        TelegramUpdateDTO update = buildUpdate(chatId, "/ayuda", "User");

        String response = telegramBotService.processUpdate(update);

        assertEquals("Comando no reconocido. Usa /start o /catalogo.", response);
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
