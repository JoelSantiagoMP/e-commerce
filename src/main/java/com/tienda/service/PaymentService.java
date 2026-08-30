package com.tienda.service;

import com.tienda.dto.OrderDTO;
import com.tienda.dto.PaymentResponseDTO;
import com.tienda.dto.WebhookNotificationDTO;
import com.tienda.entity.Order;
import com.tienda.entity.OrderStatus;
import com.tienda.exception.PaymentAlreadyProcessedException;
import com.tienda.exception.ResourceNotFoundException;
import com.tienda.repository.OrderRepository;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final Set<String> APPROVED_STATUSES = Set.of("APPROVED", "PAID");
    private static final String CHECKOUT_BASE_URL = "https://checkout.simulator.local/pay";

    private final OrderRepository orderRepository;
    private final OrderService orderService;

    @Transactional(readOnly = true)
    public PaymentResponseDTO processPaymentInit(Long orderId, String paymentMethod) {
        Order order = findPendingOrder(orderId);

        String paymentReference = buildPaymentReference(orderId);
        String normalizedMethod = paymentMethod.trim().toUpperCase(Locale.ROOT);

        return PaymentResponseDTO.builder()
                .orderId(orderId)
                .paymentReference(paymentReference)
                .checkoutUrl(CHECKOUT_BASE_URL + "/" + paymentReference + "?method=" + normalizedMethod)
                .paymentMethod(normalizedMethod)
                .amount(order.getTotalAmount())
                .status("PENDING_PAYMENT")
                .message("Intención de pago creada. Redirigir al cliente al checkout simulado.")
                .build();
    }

    @Transactional
    public OrderDTO processWebhook(WebhookNotificationDTO payload) {
        Long orderId = resolveOrderId(payload);
        String normalizedStatus = payload.getStatus().trim().toUpperCase(Locale.ROOT);

        if (!APPROVED_STATUSES.contains(normalizedStatus)) {
            throw new IllegalArgumentException(
                    "Estado de pago no soportado para confirmación: " + payload.getStatus());
        }

        return orderService.confirmOrderAfterPayment(orderId);
    }

    private Order findPendingOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Orden no encontrada con id: " + orderId));

        if (order.getStatus() != OrderStatus.PENDING) {
            throw new PaymentAlreadyProcessedException(
                    "La orden " + orderId + " no puede iniciar pago. Estado actual: " + order.getStatus());
        }

        return order;
    }

    private Long resolveOrderId(WebhookNotificationDTO payload) {
        if (payload.getOrderId() != null) {
            return payload.getOrderId();
        }

        if (payload.getPaymentReference() == null || payload.getPaymentReference().isBlank()) {
            throw new IllegalArgumentException(
                    "Debe proporcionar orderId o paymentReference en la notificación del webhook");
        }

        String reference = payload.getPaymentReference().trim();
        if (!reference.startsWith("REF-ORDER-")) {
            throw new IllegalArgumentException("Referencia de pago inválida: " + reference);
        }

        String orderIdPart = reference.substring("REF-ORDER-".length());
        int separatorIndex = orderIdPart.indexOf('-');
        if (separatorIndex > 0) {
            orderIdPart = orderIdPart.substring(0, separatorIndex);
        }

        try {
            return Long.parseLong(orderIdPart);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("No se pudo resolver el orderId desde: " + reference);
        }
    }

    private String buildPaymentReference(Long orderId) {
        return "REF-ORDER-" + orderId + "-" + System.currentTimeMillis();
    }
}
