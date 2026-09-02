package com.tienda.telegram.service;

import com.tienda.dto.OrderDTO;
import com.tienda.dto.OrderItemDTO;
import com.tienda.entity.Customer;
import com.tienda.repository.CustomerRepository;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramOrderNotificationService {

    private final TelegramClientService telegramClientService;
    private final CustomerRepository customerRepository;

    public void notifyOrderConfirmed(OrderDTO order) {
        if (order == null || order.getCustomerId() == null) {
            return;
        }

        customerRepository.findById(order.getCustomerId())
                .map(Customer::getTelegramChatId)
                .ifPresent(chatId -> {
                    String message = buildOrderConfirmedMessage(order);
                    telegramClientService.sendMessage(chatId, message);
                    log.info("Notificación de orden confirmada enviada. orderId={}, chatId={}",
                            order.getId(), chatId);
                });
    }

    private String buildOrderConfirmedMessage(OrderDTO order) {
        StringBuilder message = new StringBuilder("""
                ✅ *¡Tu pedido ha sido confirmado!*
                *Orden #* %d — Estado: *%s*
                
                """.formatted(order.getId(), order.getStatus()));

        if (order.getItems() != null && !order.getItems().isEmpty()) {
            message.append("🛒 *Resumen de tu compra:*\n\n");
            for (OrderItemDTO item : order.getItems()) {
                BigDecimal subtotal = item.getUnitPrice() != null
                        ? item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity()))
                        : BigDecimal.ZERO;
                message.append("🚗 *").append(item.getProductName()).append("*\n");
                message.append("• SKU: `").append(item.getVariantSku()).append("`\n");
                message.append("• Cantidad: ").append(item.getQuantity()).append("\n");
                message.append("• Subtotal: ").append(formatCop(subtotal)).append("\n\n");
            }
        }

        message.append("• *Total:* ").append(formatCop(order.getTotalAmount())).append("\n\n");
        message.append("Tu pago fue validado. Un asesor coordinará el despacho de tus repuestos. ¡Gracias por tu compra! 🚗");
        return message.toString().trim();
    }

    private String formatCop(BigDecimal amount) {
        if (amount == null) {
            return "$0";
        }
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(new Locale("es", "CO"));
        symbols.setGroupingSeparator('.');
        DecimalFormat formatter = new DecimalFormat("$#,##0", symbols);
        return formatter.format(amount);
    }
}
