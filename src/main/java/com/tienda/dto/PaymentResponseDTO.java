package com.tienda.dto;

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
public class PaymentResponseDTO {

    private Long orderId;
    private String paymentReference;
    private String checkoutUrl;
    private String paymentMethod;
    private BigDecimal amount;
    private String status;
    private String message;
}
