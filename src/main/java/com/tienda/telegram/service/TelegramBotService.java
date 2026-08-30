package com.tienda.telegram.service;

import com.tienda.config.MetricsConfig;
import com.tienda.dto.CategoryDTO;
import com.tienda.dto.ProductDTO;
import com.tienda.dto.ProductVariantDTO;
import com.tienda.entity.Customer;
import com.tienda.repository.CustomerRepository;
import com.tienda.service.OrderService;
import com.tienda.service.ProductService;
import com.tienda.telegram.dto.TelegramChatDTO;
import com.tienda.telegram.dto.TelegramMessageDTO;
import com.tienda.telegram.dto.TelegramUpdateDTO;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramBotService {

    private final ProductService productService;
    private final OrderService orderService;
    private final CustomerRepository customerRepository;
    private final TelegramClientService telegramClientService;
    private final MeterRegistry meterRegistry;

    public String processUpdate(TelegramUpdateDTO update) {
        if (update == null || update.getMessage() == null) {
            log.warn("Update recibido sin mensaje válido. updateId={}",
                    update != null ? update.getUpdateId() : null);
            return "Mensaje no procesable.";
        }

        TelegramMessageDTO message = update.getMessage();
        TelegramChatDTO chat = message.getChat();

        if (chat == null || chat.getId() == null) {
            log.warn("Mensaje sin chat válido. messageId={}", message.getMessageId());
            return "Chat no identificado.";
        }

        Long chatId = chat.getId();
        String text = message.getText();

        if (text == null || text.isBlank()) {
            log.info("Mensaje sin texto recibido de chatId={}", chatId);
            return "Comando no reconocido.";
        }

        String command = text.trim().split("\\s+")[0];
        log.info("Procesando comando '{}' de chatId={}", command, chatId);
        recordTelegramCommand(command);

        String response = switch (command) {
            case "/start" -> handleStart(chatId, chat);
            case "/catalogo" -> handleCatalogo();
            default -> {
                log.info("Comando no soportado: {}", command);
                yield "Comando no reconocido. Usa /start o /catalogo.";
            }
        };

        telegramClientService.sendMessage(chatId, response);

        return response;
    }

    private String handleStart(Long chatId, TelegramChatDTO chat) {
        Customer customer = customerRepository.findByTelegramChatId(chatId)
                .orElseGet(() -> {
                    String fullName = chat.getFirstName() != null ? chat.getFirstName() : "Usuario";

                    Customer newCustomer = Customer.builder()
                            .telegramChatId(chatId)
                            .fullName(fullName)
                            .createdAt(LocalDateTime.now())
                            .build();

                    Customer saved = customerRepository.save(newCustomer);
                    log.info("Nuevo cliente registrado vía Telegram: chatId={}, nombre={}", chatId, fullName);
                    return saved;
                });

        log.info("Comando /start procesado para chatId={}, customerId={}", chatId, customer.getId());

        return "¡Bienvenido, " + customer.getFullName() + "! 🛍️\n"
                + "Soy el bot de la tienda. Usa /catalogo para ver productos disponibles.";
    }

    private String handleCatalogo() {
        List<CategoryDTO> categories = productService.getAllActiveCategories();

        if (categories.isEmpty()) {
            log.info("Catálogo consultado: sin categorías activas");
            return "No hay productos disponibles en este momento.";
        }

        StringBuilder catalog = new StringBuilder("📦 *Catálogo de productos*\n\n");

        for (CategoryDTO category : categories) {
            List<ProductDTO> products = productService.getActiveProductsByCategory(category.getId());

            if (products.isEmpty()) {
                continue;
            }

            catalog.append("▸ ").append(category.getName()).append("\n");

            for (ProductDTO product : products) {
                catalog.append("  • ").append(product.getName()).append("\n");

                List<ProductVariantDTO> variants = productService.getActiveVariantsByProductId(product.getId());

                for (ProductVariantDTO variant : variants) {
                    BigDecimal price = variant.getPriceOverride() != null
                            ? variant.getPriceOverride()
                            : product.getBasePrice();

                    catalog.append("    - SKU: ").append(variant.getSku())
                            .append(" | Precio: $").append(price)
                            .append(" | Stock: ").append(variant.getStock())
                            .append("\n");
                }

                catalog.append("\n");
            }
        }

        log.info("Catálogo generado con {} categorías activas", categories.size());
        return catalog.toString().trim();
    }

    private void recordTelegramCommand(String command) {
        String commandTag = switch (command) {
            case "/start" -> "start";
            case "/catalogo" -> "catalogo";
            default -> "unknown";
        };

        meterRegistry.counter(MetricsConfig.TELEGRAM_COMMANDS_METRIC, "command", commandTag).increment();
    }
}
