package com.tienda.telegram.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tienda.telegram.dto.PendingOrderLine;
import com.tienda.telegram.dto.ShownProduct;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PurchaseIntentResolverTest {

    private PurchaseIntentResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new PurchaseIntentResolver();
    }

    @Test
    void resolveFromShownProducts_doesNotTreatOilViscosityAsQuantity() {
        List<ShownProduct> shown = List.of(
                shownProduct("FRN-CHE-001", "Pastillas de Freno Cerámicas", "Corsa / Aveo 1.4", 15),
                shownProduct("LUB-CHE-010", "Kit de Filtros + Aceite 20W50 Sintético", "4 Litros + Filtro Aceite Corsa", 20));

        String message = normalize(
                "quiero las pastillas de freno del corsa, y el kit de filtros y aceite 20w50 para el corsa");

        List<PendingOrderLine> resolved = resolver.resolveFromShownProducts(message, shown);

        assertEquals(2, resolved.size());
        assertEquals("FRN-CHE-001", resolved.get(0).sku());
        assertEquals(1, resolved.get(0).quantity());
        assertEquals("LUB-CHE-010", resolved.get(1).sku());
        assertEquals(1, resolved.get(1).quantity());
    }

    @Test
    void resolveFromShownProducts_filtersByApplicationWhenVehicleIsMentioned() {
        List<ShownProduct> shown = List.of(
                shownProduct("FRN-CHE-001", "Pastillas de Freno Cerámicas", "Corsa / Aveo 1.4", 15),
                shownProduct("FRN-REN-002", "Pastillas de Freno Cerámicas", "Logan / Sandero 1.6", 10),
                shownProduct("FRN-TOY-003", "Pastillas de Freno Cerámicas", "Hilux 2.4 / Fortuner", 6));

        List<PendingOrderLine> resolved = resolver.resolveFromShownProducts(
                normalize("quiero las pastillas de freno del corsa"), shown);

        assertEquals(1, resolved.size());
        assertEquals("FRN-CHE-001", resolved.get(0).sku());
        assertEquals(1, resolved.get(0).quantity());
    }

    @Test
    void resolveFromShownProducts_parsesExplicitQuantityWithUnits() {
        List<ShownProduct> shown = List.of(
                shownProduct("FRN-CHE-001", "Pastillas de Freno Cerámicas", "Corsa / Aveo 1.4", 15));

        List<PendingOrderLine> resolved = resolver.resolveFromShownProducts(
                normalize("quiero 3 juegos de pastillas del corsa"), shown);

        assertEquals(1, resolved.size());
        assertEquals(3, resolved.get(0).quantity());
    }

    @Test
    void resolveFromShownProducts_ambos_returnsAllProductsWithDefaultQuantity() {
        List<ShownProduct> shown = List.of(
                shownProduct("FRN-TOY-003", "Pastillas de Freno"),
                shownProduct("SUS-CHE-021", "Amortiguadores a Gas"));

        List<PendingOrderLine> resolved = resolver.resolveFromShownProducts(
                "si ambos que me mostraste quiero los 2 los comprare", shown);

        assertEquals(2, resolved.size());
        assertEquals("FRN-TOY-003", resolved.get(0).sku());
        assertEquals(1, resolved.get(0).quantity());
        assertEquals("SUS-CHE-021", resolved.get(1).sku());
        assertEquals(1, resolved.get(1).quantity());
    }

    @Test
    void looksLikePurchaseIntent_detectsNaturalConfirmation() {
        assertTrue(resolver.looksLikePurchaseIntent("si ambos que me mostraste quiero los 2"));
        assertTrue(resolver.looksLikePurchaseIntent("los comprare"));
        assertFalse(resolver.looksLikePurchaseIntent("cuanto cuesta"));
    }

    @Test
    void isAffirmative_detectsShortConfirmations() {
        assertTrue(resolver.isAffirmative("si"));
        assertTrue(resolver.isAffirmative("dale confirmo"));
        assertFalse(resolver.isAffirmative("no gracias"));
    }

    private ShownProduct shownProduct(String sku, String name) {
        return shownProduct(sku, name, "Aplicación demo", 5);
    }

    private ShownProduct shownProduct(String sku, String name, String application, int stock) {
        return new ShownProduct(sku, name, application, new BigDecimal("100000"), stock);
    }

    private String normalize(String text) {
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
