package com.tienda.telegram.service;

import com.tienda.telegram.dto.PendingOrderLine;
import com.tienda.telegram.dto.ShownProduct;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class PurchaseIntentResolver {

    private static final List<String> PURCHASE_KEYWORDS = List.of(
            "comprar", "comprare", "compro", "quiero", "llevar", "pedir", "pedido",
            "me interesa", "lo quiero", "los quiero", "dame", "enviame", "envíame");

    private static final List<String> REFERENCE_ALL_KEYWORDS = List.of(
            "ambos", "las dos", "los dos", "los 2", "las 2", "esos dos", "esas dos",
            "los que me mostraste", "lo que me mostraste", "los que mostraste",
            "esos que me mostraste", "los mismos", "igual esos", "cada uno", "un juego cada");

    private static final List<String> AFFIRMATIVE_KEYWORDS = List.of(
            "si", "sí", "confirmo", "confirmar", "dale", "ok", "okay", "perfecto",
            "adelante", "de acuerdo", "listo", "hazlo", "procede", "acepto");

    private static final List<String> NEGATIVE_KEYWORDS = List.of(
            "no", "cancelar", "cancela", "mejor no", "olvidalo", "olvídalo", "no quiero");

    private static final Pattern QUANTITY_PATTERN = Pattern.compile(
            "(\\d+)\\s*(unidad(?:es)?|juego(?:s)?|par(?:es)?|pieza(?:s)?)?",
            Pattern.CASE_INSENSITIVE);

    public boolean looksLikePurchaseIntent(String normalizedText) {
        if (normalizedText.isBlank()) {
            return false;
        }
        return containsAny(normalizedText, PURCHASE_KEYWORDS)
                || containsAny(normalizedText, REFERENCE_ALL_KEYWORDS)
                || (isAffirmative(normalizedText) && normalizedText.length() < 40);
    }

    public boolean isAffirmative(String normalizedText) {
        if (normalizedText.isBlank()) {
            return false;
        }
        return AFFIRMATIVE_KEYWORDS.stream().anyMatch(keyword -> matchesKeyword(normalizedText, keyword));
    }

    public boolean isNegative(String normalizedText) {
        if (normalizedText.isBlank()) {
            return false;
        }
        return NEGATIVE_KEYWORDS.stream().anyMatch(keyword -> matchesKeyword(normalizedText, keyword));
    }

    public List<PendingOrderLine> resolveFromShownProducts(String normalizedText, List<ShownProduct> shownProducts) {
        if (shownProducts == null || shownProducts.isEmpty()) {
            return List.of();
        }

        if (shownProducts.size() == 1) {
            int quantity = resolveQuantity(normalizedText, 1);
            return List.of(new PendingOrderLine(shownProducts.get(0).sku(), quantity));
        }

        if (referencesAllProducts(normalizedText)) {
            int quantityPerItem = resolveQuantityPerItem(normalizedText);
            return shownProducts.stream()
                    .map(product -> new PendingOrderLine(product.sku(), quantityPerItem))
                    .toList();
        }

        List<PendingOrderLine> matched = matchByProductName(normalizedText, shownProducts);
        if (!matched.isEmpty()) {
            return matched;
        }

        if (looksLikePurchaseIntent(normalizedText)) {
            int quantityPerItem = resolveQuantityPerItem(normalizedText);
            return shownProducts.stream()
                    .map(product -> new PendingOrderLine(product.sku(), quantityPerItem))
                    .toList();
        }

        return List.of();
    }

    private List<PendingOrderLine> matchByProductName(String normalizedText, List<ShownProduct> shownProducts) {
        List<PendingOrderLine> matches = new ArrayList<>();
        for (ShownProduct product : shownProducts) {
            String normalizedName = normalize(product.productName());
            if (normalizedName.isBlank()) {
                continue;
            }
            String[] tokens = normalizedName.split("\\s+");
            int significantTokens = 0;
            for (String token : tokens) {
                if (token.length() < 4) {
                    continue;
                }
                significantTokens++;
                if (normalizedText.contains(token)) {
                    matches.add(new PendingOrderLine(product.sku(), resolveQuantity(normalizedText, 1)));
                    break;
                }
            }
            if (significantTokens == 0 && normalizedText.contains(normalizedName)) {
                matches.add(new PendingOrderLine(product.sku(), resolveQuantity(normalizedText, 1)));
            }
        }
        return matches;
    }

    private boolean referencesAllProducts(String normalizedText) {
        return containsAny(normalizedText, REFERENCE_ALL_KEYWORDS);
    }

    private int resolveQuantityPerItem(String normalizedText) {
        Matcher eachMatcher = Pattern.compile(
                "(\\d+)\\s*(unidad(?:es)?|juego(?:s)?|par(?:es)?|pieza(?:s)?)\\s+cada",
                Pattern.CASE_INSENSITIVE).matcher(normalizedText);
        if (eachMatcher.find()) {
            return Integer.parseInt(eachMatcher.group(1));
        }
        if (normalizedText.contains("cada uno") || normalizedText.contains("un juego cada")) {
            return 1;
        }
        if (referencesAllProducts(normalizedText)) {
            return 1;
        }
        return resolveQuantity(normalizedText, 1);
    }

    private int resolveQuantity(String normalizedText, int defaultQuantity) {
        Matcher matcher = QUANTITY_PATTERN.matcher(normalizedText);
        if (matcher.find()) {
            try {
                int quantity = Integer.parseInt(matcher.group(1));
                if (quantity > 0) {
                    return quantity;
                }
            } catch (NumberFormatException ignored) {
                // Usar cantidad por defecto
            }
        }
        return defaultQuantity;
    }

    private boolean containsAny(String text, List<String> keywords) {
        return keywords.stream().anyMatch(keyword -> matchesKeyword(text, keyword));
    }

    private boolean matchesKeyword(String text, String keyword) {
        if (text.equals(keyword)) {
            return true;
        }
        return text.contains(" " + keyword + " ")
                || text.startsWith(keyword + " ")
                || text.endsWith(" " + keyword);
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
