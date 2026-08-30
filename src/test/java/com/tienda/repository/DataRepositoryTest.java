package com.tienda.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.tienda.entity.Category;
import com.tienda.entity.Product;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
class DataRepositoryTest {

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ProductRepository productRepository;

    @BeforeEach
    void seedTestData() {
        if (categoryRepository.count() > 0) {
            return;
        }

        Category ropa = categoryRepository.save(Category.builder()
                .name("Ropa")
                .description("Prendas de vestir")
                .isActive(true)
                .build());

        productRepository.save(Product.builder()
                .category(ropa)
                .name("Camiseta Básica")
                .description("Camiseta de algodón")
                .basePrice(new BigDecimal("29.99"))
                .isActive(true)
                .createdAt(LocalDateTime.now())
                .build());
    }

    @Test
    void verifyDatabaseSeedAndRelationsData() {
        Category ropa = categoryRepository.findAll().stream()
                .filter(category -> "Ropa".equals(category.getName()))
                .findFirst()
                .orElse(null);

        assertNotNull(ropa, "La categoría 'Ropa' debe existir");

        List<Product> activeProducts = productRepository.findByCategoryIdAndIsActiveTrue(ropa.getId());

        assertFalse(activeProducts.isEmpty(), "Debe haber al menos un producto activo en la categoría 'Ropa'");

        activeProducts.forEach(product ->
                assertThat(product.getBasePrice())
                        .as("El precio base de '%s' debe ser mayor a cero", product.getName())
                        .isGreaterThan(BigDecimal.ZERO)
        );
    }
}
