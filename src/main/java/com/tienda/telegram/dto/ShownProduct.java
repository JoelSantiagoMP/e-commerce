package com.tienda.telegram.dto;

import java.math.BigDecimal;

public record ShownProduct(
        String sku,
        String productName,
        String application,
        BigDecimal unitPrice,
        int stock) {}
