package com.tienda.telegram.service;

import com.tienda.entity.Product;
import com.tienda.entity.ProductVariant;
import com.tienda.repository.ProductVariantRepository;
import com.tienda.telegram.dto.PendingOrderLine;
import com.tienda.telegram.dto.ShownProduct;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ChatSessionService {

    private static final Duration SESSION_TTL = Duration.ofMinutes(45);

    private final ProductVariantRepository productVariantRepository;
    private final PurchaseIntentResolver purchaseIntentResolver;
    private final Map<Long, ChatSession> sessions = new ConcurrentHashMap<>();

    public void recordShownSkus(Long chatId, List<String> skus) {
        if (chatId == null || skus == null || skus.isEmpty()) {
            return;
        }

        ChatSession session = getOrCreate(chatId);
        Map<String, ShownProduct> productsBySku = new LinkedHashMap<>();
        for (ShownProduct existing : session.lastShownProducts()) {
            productsBySku.put(existing.sku(), existing);
        }

        for (String rawSku : skus) {
            String sku = rawSku.trim().toUpperCase();
            productVariantRepository.findBySku(sku)
                    .filter(variant -> Boolean.TRUE.equals(variant.getIsActive()))
                    .ifPresent(variant -> productsBySku.put(sku, toShownProduct(variant)));
        }

        session.lastShownProducts().clear();
        session.lastShownProducts().addAll(productsBySku.values());
        session.touch();
    }

    public void recordShownProductsFromCatalog(Long chatId, List<String> skus) {
        recordShownSkus(chatId, skus);
    }

    public Optional<List<PendingOrderLine>> getPendingOrder(Long chatId) {
        ChatSession session = sessions.get(chatId);
        if (session == null || session.pendingOrder().isEmpty() || isExpired(session)) {
            return Optional.empty();
        }
        return Optional.of(List.copyOf(session.pendingOrder()));
    }

    public void setPendingOrder(Long chatId, List<PendingOrderLine> lines) {
        ChatSession session = getOrCreate(chatId);
        session.pendingOrder().clear();
        session.pendingOrder().addAll(lines);
        session.awaitingConfirmation = true;
        session.touch();
    }

    public void clearPendingOrder(Long chatId) {
        ChatSession session = sessions.get(chatId);
        if (session == null) {
            return;
        }
        session.pendingOrder().clear();
        session.awaitingConfirmation = false;
        session.touch();
    }

    public boolean isAwaitingConfirmation(Long chatId) {
        ChatSession session = sessions.get(chatId);
        return session != null && !isExpired(session) && session.awaitingConfirmation;
    }

    public boolean hasShownProducts(Long chatId) {
        ChatSession session = sessions.get(chatId);
        return session != null && !isExpired(session) && !session.lastShownProducts().isEmpty();
    }

    public Optional<List<PendingOrderLine>> tryResolvePurchaseIntent(Long chatId, String normalizedText) {
        ChatSession session = sessions.get(chatId);
        if (session == null || isExpired(session) || session.lastShownProducts().isEmpty()) {
            return Optional.empty();
        }

        if (!purchaseIntentResolver.looksLikePurchaseIntent(normalizedText)) {
            return Optional.empty();
        }

        List<PendingOrderLine> resolved =
                purchaseIntentResolver.resolveFromShownProducts(normalizedText, session.lastShownProducts());
        if (resolved.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(resolved);
    }

    public String buildGeminiContext(Long chatId) {
        ChatSession session = sessions.get(chatId);
        if (session == null || isExpired(session)) {
            return "";
        }

        StringBuilder context = new StringBuilder();
        if (!session.lastShownProducts().isEmpty()) {
            context.append("Productos mostrados recientemente al cliente:\n");
            for (ShownProduct product : session.lastShownProducts()) {
                context.append("- ")
                        .append(product.sku())
                        .append(": ")
                        .append(product.productName());
                if (product.application() != null && !product.application().isBlank()) {
                    context.append(" (").append(product.application()).append(")");
                }
                context.append(" — $")
                        .append(product.unitPrice().longValue())
                        .append(" COP, stock: ")
                        .append(product.stock())
                        .append("\n");
            }
            context.append("""
                    Si el cliente confirma compra refiriéndose a estos productos (por ejemplo "ambos", \
                    "los 2", "los que me mostraste", "sí los quiero"), NO pidas el SKU. \
                    Resume el pedido con nombres y cantidades, e invoca prepararPedido con los SKUs inferidos.
                    """);
        }

        if (!session.pendingOrder().isEmpty()) {
            context.append("\nPedido pendiente de confirmación:\n");
            for (PendingOrderLine line : session.pendingOrder()) {
                context.append("- ").append(line.sku()).append(" x").append(line.quantity()).append("\n");
            }
            context.append("""
                    Si el cliente confirma ("sí", "dale", "confirmo"), invoca confirmarPedido. \
                    Si quiere cambiar algo, ayúdale a ajustar cantidades o productos.
                    """);
        }

        return context.toString().trim();
    }

    private ChatSession getOrCreate(Long chatId) {
        return sessions.computeIfAbsent(chatId, ChatSession::new);
    }

    private boolean isExpired(ChatSession session) {
        return session.lastActivity().isBefore(LocalDateTime.now().minus(SESSION_TTL));
    }

    private ShownProduct toShownProduct(ProductVariant variant) {
        Product product = variant.getProduct();
        BigDecimal price = variant.getPriceOverride();
        if (price == null && product != null) {
            price = product.getBasePrice();
        }
        if (price == null) {
            price = BigDecimal.ZERO;
        }

        String application = resolveApplication(variant);
        String productName = product != null ? product.getName() : variant.getSku();

        return new ShownProduct(
                variant.getSku(),
                productName,
                application,
                price,
                variant.getStock() != null ? variant.getStock() : 0);
    }

    private String resolveApplication(ProductVariant variant) {
        if (variant.getColor() != null && !variant.getColor().isBlank()) {
            return variant.getColor();
        }
        if (variant.getSize() != null && !variant.getSize().isBlank()) {
            return variant.getSize();
        }
        return "";
    }

    private static final class ChatSession {
        private final Long chatId;
        private final List<ShownProduct> lastShownProducts = new ArrayList<>();
        private final List<PendingOrderLine> pendingOrder = new ArrayList<>();
        private boolean awaitingConfirmation;
        private LocalDateTime lastActivity;

        private ChatSession(Long chatId) {
            this.chatId = chatId;
            this.lastActivity = LocalDateTime.now();
        }

        private List<ShownProduct> lastShownProducts() {
            return lastShownProducts;
        }

        private List<PendingOrderLine> pendingOrder() {
            return pendingOrder;
        }

        private LocalDateTime lastActivity() {
            return lastActivity;
        }

        private void touch() {
            lastActivity = LocalDateTime.now();
        }
    }
}
