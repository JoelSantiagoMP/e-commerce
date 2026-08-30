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
public class ProductVariantDTO {

    private Long id;
    private Long productId;
    private String sku;
    private String size;
    private String color;
    private Integer stock;
    private BigDecimal priceOverride;
    private Boolean isActive;
}
