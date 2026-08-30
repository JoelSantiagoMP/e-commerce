package com.tienda.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MetricsConfig {

    public static final String TELEGRAM_COMMANDS_METRIC = "telegram.bot.commands.total";

    @Bean
    public Counter telegramCommandsCounter(MeterRegistry meterRegistry) {
        return Counter.builder(TELEGRAM_COMMANDS_METRIC)
                .description("Total de comandos recibidos por el bot de Telegram")
                .register(meterRegistry);
    }
}
