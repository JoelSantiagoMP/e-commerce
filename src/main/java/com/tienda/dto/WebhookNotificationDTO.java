package com.tienda.dto;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebhookNotificationDTO {

    private Long orderId;

    private String paymentReference;

    @NotBlank(message = "El estado del pago es obligatorio")
    private String status;

    private String transactionId;

    private String paymentMethod;

    private BigDecimal amount;
}
