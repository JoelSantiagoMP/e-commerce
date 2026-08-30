package com.tienda.config;

import com.tienda.entity.Category;
import com.tienda.entity.Customer;
import com.tienda.entity.Product;
import com.tienda.entity.ProductVariant;
import com.tienda.repository.CategoryRepository;
import com.tienda.repository.CustomerRepository;
import com.tienda.repository.ProductRepository;
import com.tienda.repository.ProductVariantRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final ProductVariantRepository productVariantRepository;
    private final CustomerRepository customerRepository;

    @Override
    public void run(String... args) {
        if (categoryRepository.count() > 0) {
            log.info("Base de datos ya contiene categorías. Se omite la carga del seeder.");
            return;
        }

        log.info("Iniciando carga de datos de prueba...");

        List<Customer> customers = customerRepository.saveAll(List.of(
                Customer.builder()
                        .fullName("Joel Martínez")
                        .telegramChatId(123456789L)
                        .phone("joel@example.com")
                        .address("Bucaramanga")
                        .createdAt(LocalDateTime.now())
                        .build(),
                Customer.builder()
                        .fullName("Cliente Test")
                        .telegramChatId(987654321L)
                        .phone("test@example.com")
                        .address("Central")
                        .createdAt(LocalDateTime.now())
                        .build()
        ));

        List<Category> categories = categoryRepository.saveAll(List.of(
                Category.builder()
                        .name("Ropa Deportiva")
                        .description("Prendas para entrenamiento y actividad física")
                        .isActive(true)
                        .build(),
                Category.builder()
                        .name("Accesorios")
                        .description("Complementos deportivos y de moda")
                        .isActive(true)
                        .build()
        ));

        Category ropaDeportiva = categories.get(0);
        Category accesorios = categories.get(1);

        List<Product> products = productRepository.saveAll(List.of(
                Product.builder()
                        .category(ropaDeportiva)
                        .name("Camiseta Deportiva Pro")
                        .description("Camiseta transpirable de alto rendimiento")
                        .basePrice(new BigDecimal("34.99"))
                        .isActive(true)
                        .createdAt(LocalDateTime.now())
                        .build(),
                Product.builder()
                        .category(accesorios)
                        .name("Gorra Running Elite")
                        .description("Gorra ligera con protección UV")
                        .basePrice(new BigDecimal("22.50"))
                        .isActive(true)
                        .createdAt(LocalDateTime.now())
                        .build()
        ));

        Product camiseta = products.get(0);
        Product gorra = products.get(1);

        productVariantRepository.saveAll(List.of(
                ProductVariant.builder()
                        .product(camiseta)
                        .sku("TSHIRT-BLK-M")
                        .size("M")
                        .color("Negro")
                        .stock(10)
                        .isActive(true)
                        .build(),
                ProductVariant.builder()
                        .product(camiseta)
                        .sku("TSHIRT-WHT-L")
                        .size("L")
                        .color("Blanco")
                        .stock(10)
                        .isActive(true)
                        .build(),
                ProductVariant.builder()
                        .product(gorra)
                        .sku("CAP-BLK-UNI")
                        .size("UNI")
                        .color("Negro")
                        .stock(10)
                        .isActive(true)
                        .build(),
                ProductVariant.builder()
                        .product(gorra)
                        .sku("CAP-RED-UNI")
                        .size("UNI")
                        .color("Rojo")
                        .stock(10)
                        .isActive(true)
                        .build()
        ));

        log.info(
                "Datos de prueba cargados con éxito: {} clientes, {} categorías, {} productos y {} variantes.",
                customers.size(),
                categories.size(),
                products.size(),
                4
        );
    }
}
