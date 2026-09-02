package com.tienda.telegram.service;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tienda.dto.OrderDTO;
import com.tienda.dto.OrderItemDTO;
import com.tienda.entity.Customer;
import com.tienda.entity.OrderStatus;
import com.tienda.repository.CustomerRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TelegramOrderNotificationServiceTest {

    @Mock
    private TelegramClientService telegramClientService;

    @Mock
    private CustomerRepository customerRepository;

    @InjectMocks
    private TelegramOrderNotificationService notificationService;

    @Test
    void notifyOrderConfirmed_sendsMessageToCustomerTelegramChat() {
        Customer customer = Customer.builder()
                .id(5L)
                .telegramChatId(573001234567L)
                .fullName("Cliente Telegram")
                .createdAt(LocalDateTime.now())
                .build();

        OrderDTO order = OrderDTO.builder()
                .id(99L)
                .customerId(5L)
                .status(OrderStatus.CONFIRMED)
                .totalAmount(new BigDecimal("325000"))
                .items(List.of(
                        OrderItemDTO.builder()
                                .productName("Pastillas Fortuner")
                                .variantSku("FRN-TOY-003")
                                .quantity(1)
                                .unitPrice(new BigDecimal("145000"))
                                .build(),
                        OrderItemDTO.builder()
                                .productName("Amortiguadores Traseros Corsa")
                                .variantSku("SUS-CHE-021")
                                .quantity(1)
                                .unitPrice(new BigDecimal("180000"))
                                .build()))
                .build();

        when(customerRepository.findById(5L)).thenReturn(Optional.of(customer));

        notificationService.notifyOrderConfirmed(order);

        verify(telegramClientService).sendMessage(
                eq(573001234567L),
                argThat(message -> message.contains("confirmado") && message.contains("FRN-TOY-003")));
    }

    @Test
    void notifyOrderConfirmed_skipsWhenCustomerHasNoTelegramChat() {
        Customer customer = Customer.builder()
                .id(5L)
                .telegramChatId(null)
                .fullName("Cliente Web")
                .createdAt(LocalDateTime.now())
                .build();

        OrderDTO order = OrderDTO.builder()
                .id(99L)
                .customerId(5L)
                .status(OrderStatus.CONFIRMED)
                .totalAmount(new BigDecimal("85000"))
                .build();

        when(customerRepository.findById(5L)).thenReturn(Optional.of(customer));

        notificationService.notifyOrderConfirmed(order);

        verify(telegramClientService, never()).sendMessage(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
