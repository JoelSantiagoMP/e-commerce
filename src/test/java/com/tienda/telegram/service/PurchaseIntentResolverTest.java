package com.tienda.telegram.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tienda.telegram.dto.PendingOrderLine;
import com.tienda.telegram.dto.ShownProduct;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PurchaseIntentResolverTest {

    private PurchaseIntentResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new PurchaseIntentResolver();
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
        return new ShownProduct(sku, name, "Aplicación demo", new BigDecimal("100000"), 5);
    }
}
