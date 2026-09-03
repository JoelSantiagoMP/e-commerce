package com.tienda.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class RenderDatabaseConfigValidatorTest {

    private final RenderDatabaseConfigValidator validator = new RenderDatabaseConfigValidator();

    @Test
    void initialize_skipsValidationOutsideRender() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            TestPropertyValues.of("RENDER=false").applyTo(context);
            context.refresh();

            assertDoesNotThrow(() -> validator.initialize(context));
        }
    }

    @Test
    void initialize_failsWhenDbUrlPointsToLocalhostOnRender() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            TestPropertyValues.of(
                            "RENDER=true",
                            "DB_URL=jdbc:mysql://localhost:3306/defaultdb",
                            "DB_USER=avnadmin",
                            "DB_PASSWORD=secret")
                    .applyTo(context);

            IllegalStateException exception =
                    assertThrows(IllegalStateException.class, () -> validator.initialize(context));

            assertTrue(exception.getMessage().contains("DB_URL"));
        }
    }

    @Test
    void initialize_acceptsValidRenderDatabaseConfig() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            TestPropertyValues.of(
                            "RENDER=true",
                            "DB_URL=jdbc:mysql://aiven-host:24709/defaultdb?sslMode=REQUIRED",
                            "DB_USER=avnadmin",
                            "DB_PASSWORD=secret")
                    .applyTo(context);

            assertDoesNotThrow(() -> validator.initialize(context));
        }
    }
}
