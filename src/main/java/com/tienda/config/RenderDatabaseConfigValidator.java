package com.tienda.config;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

public class RenderDatabaseConfigValidator
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        Environment environment = applicationContext.getEnvironment();

        if (!"true".equalsIgnoreCase(environment.getProperty("RENDER"))) {
            return;
        }

        String dbUrl = environment.getProperty("DB_URL", "");
        String dbUser = environment.getProperty("DB_USER", "");
        String dbPassword = environment.getProperty("DB_PASSWORD", "");

        if (dbUrl.isBlank() || dbUrl.contains("localhost") || dbUrl.contains("127.0.0.1")) {
            throw new IllegalStateException(
                    "DB_URL no está configurada para producción en Render. "
                            + "Configure la URL JDBC de Aiven en Dashboard → Environment.");
        }

        if (dbUser.isBlank() || dbPassword.isBlank()) {
            throw new IllegalStateException(
                    "DB_USER y DB_PASSWORD son obligatorios en Render. "
                            + "Configúrelos en Dashboard → Environment.");
        }
    }
}
