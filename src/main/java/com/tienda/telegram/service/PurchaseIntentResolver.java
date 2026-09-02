package com.tienda.telegram.service;

import com.tienda.telegram.dto.PendingOrderLine;
import com.tienda.telegram.dto.ShownProduct;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    private static final Pattern QUANTITY_WITH_UNIT_PATTERN = Pattern.compile(
            "(\\d+)\\s+(unidad(?:es)?|juego(?:s)?|par(?:es)?|pieza(?:s)?)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern EXPLICIT_COUNT_PATTERN = Pattern.compile(
            "\\b(?:los|las|quiero|dame|llevo|llevar)\\s+(\\d+)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CLAUSE_SPLIT_PATTERN = Pattern.compile(
            "\\s+y\\s+(?:el|la|los|las)\\s+",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern VISCOSITY_PATTERN = Pattern.compile("\\d+w\\d+", Pattern.CASE_INSENSITIVE);

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

        List<PendingOrderLine> matched = matchProducts(normalizedText, shownProducts);
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

    private List<PendingOrderLine> matchProducts(String normalizedText, List<ShownProduct> shownProducts) {
        List<String> clauses = splitPurchaseClauses(normalizedText);
        Map<String, PendingOrderLine> matchedBySku = new LinkedHashMap<>();

        for (String clause : clauses) {
            for (ShownProduct product : shownProducts) {
                if (matchesProductReference(clause, product, shownProducts)) {
                    int quantity = resolveQuantity(clause, 1);
                    matchedBySku.put(product.sku(), new PendingOrderLine(product.sku(), quantity));
                }
            }
        }

        return new ArrayList<>(matchedBySku.values());
    }

    private List<String> splitPurchaseClauses(String normalizedText) {
        String[] parts = CLAUSE_SPLIT_PATTERN.split(normalizedText);
        if (parts.length <= 1) {
            return List.of(normalizedText);
        }

        List<String> clauses = new ArrayList<>();
        for (String part : parts) {
            String clause = part.trim();
            if (!clause.isBlank()) {
                clauses.add(clause);
            }
        }
        return clauses.isEmpty() ? List.of(normalizedText) : clauses;
    }

    private boolean matchesProductReference(
            String normalizedText, ShownProduct product, List<ShownProduct> shownProducts) {
        String normalizedName = normalize(product.productName());
        if (normalizedName.isBlank()) {
            return false;
        }

        if (!matchesByNameTokens(normalizedText, normalizedName)) {
            return false;
        }

        return matchesApplicationContext(normalizedText, product.application(), shownProducts);
    }

    private boolean matchesByNameTokens(String normalizedText, String normalizedName) {
        String[] tokens = normalizedName.split("\\s+");
        int significantTokens = 0;
        for (String token : tokens) {
            if (token.length() < 4) {
                continue;
            }
            significantTokens++;
            if (normalizedText.contains(token)) {
                return true;
            }
        }
        return significantTokens == 0 && normalizedText.contains(normalizedName);
    }

    private boolean matchesApplicationContext(
            String normalizedText, String application, List<ShownProduct> shownProducts) {
        List<String> vehicleMentions = extractVehicleMentions(normalizedText, shownProducts);
        if (vehicleMentions.isEmpty()) {
            return true;
        }

        String normalizedApplication = normalize(application);
        if (normalizedApplication.isBlank()) {
            return false;
        }
        return vehicleMentions.stream().anyMatch(normalizedApplication::contains);
    }

    private List<String> extractVehicleMentions(String normalizedText, List<ShownProduct> shownProducts) {
        List<String> mentions = new ArrayList<>();
        for (ShownProduct product : shownProducts) {
            for (String token : extractApplicationTokens(normalize(product.application()))) {
                if (token.length() >= 4 && normalizedText.contains(token) && !mentions.contains(token)) {
                    mentions.add(token);
                }
            }
        }
        return mentions;
    }

    private List<String> extractApplicationTokens(String normalizedApplication) {
        List<String> tokens = new ArrayList<>();
        for (String token : normalizedApplication.split("\\s+")) {
            if (token.length() < 4 || isGenericApplicationToken(token)) {
                continue;
            }
            tokens.add(token);
        }
        return tokens;
    }

    private boolean isGenericApplicationToken(String token) {
        return token.equals("litros")
                || token.equals("filtro")
                || token.equals("aceite")
                || token.equals("delanteros")
                || token.equals("delantero")
                || token.equals("traseros")
                || token.equals("trasero")
                || token.equals("evolution");
    }

    private boolean referencesAllProducts(String normalizedText) {
        return containsAny(normalizedText, REFERENCE_ALL_KEYWORDS);
    }

    private int resolveQuantityPerItem(String normalizedText) {
        Matcher eachMatcher = Pattern.compile(
                "(\\d+)\\s*(unidad(?:es)?|juego(?:s)?|par(?:es)?|pieza(?:s)?)\\s+cada",
                Pattern.CASE_INSENSITIVE).matcher(normalizedText);
        if (eachMatcher.find()) {
            return parsePositiveQuantity(eachMatcher.group(1), 1);
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
        String sanitizedText = VISCOSITY_PATTERN.matcher(normalizedText).replaceAll(" ");

        Matcher withUnitMatcher = QUANTITY_WITH_UNIT_PATTERN.matcher(sanitizedText);
        if (withUnitMatcher.find()) {
            return parsePositiveQuantity(withUnitMatcher.group(1), defaultQuantity);
        }

        Matcher explicitCountMatcher = EXPLICIT_COUNT_PATTERN.matcher(sanitizedText);
        if (explicitCountMatcher.find()) {
            return parsePositiveQuantity(explicitCountMatcher.group(1), defaultQuantity);
        }

        return defaultQuantity;
    }

    private int parsePositiveQuantity(String rawQuantity, int defaultQuantity) {
        try {
            int quantity = Integer.parseInt(rawQuantity);
            return quantity > 0 ? quantity : defaultQuantity;
        } catch (NumberFormatException ignored) {
            return defaultQuantity;
        }
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
