package com.tienda.controller;

import com.tienda.dto.OrderDTO;
import com.tienda.dto.PaymentRequestDTO;
import com.tienda.dto.PaymentResponseDTO;
import com.tienda.dto.WebhookNotificationDTO;
import com.tienda.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping("/checkout")
    public ResponseEntity<PaymentResponseDTO> initiateCheckout(@Valid @RequestBody PaymentRequestDTO request) {
        PaymentResponseDTO response = paymentService.processPaymentInit(
                request.getOrderId(),
                request.getPaymentMethod());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/webhook")
    public ResponseEntity<OrderDTO> handleWebhook(@Valid @RequestBody WebhookNotificationDTO payload) {
        OrderDTO confirmedOrder = paymentService.processWebhook(payload);
        return ResponseEntity.ok(confirmedOrder);
    }
}
