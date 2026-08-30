package com.tienda.controller;

import com.tienda.dto.CategoryCreateDTO;
import com.tienda.dto.CategoryDTO;
import com.tienda.dto.ProductCreateDTO;
import com.tienda.dto.ProductDTO;
import com.tienda.dto.ProductVariantCreateDTO;
import com.tienda.dto.ProductVariantDTO;
import com.tienda.service.ProductService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @PostMapping("/categories")
    public ResponseEntity<CategoryDTO> createCategory(@Valid @RequestBody CategoryCreateDTO dto) {
        CategoryDTO created = productService.createCategory(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/categories")
    public ResponseEntity<List<CategoryDTO>> getAllActiveCategories() {
        return ResponseEntity.ok(productService.getAllActiveCategories());
    }

    @PostMapping
    public ResponseEntity<ProductDTO> createProduct(@Valid @RequestBody ProductCreateDTO dto) {
        ProductDTO created = productService.createProduct(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    public ResponseEntity<List<ProductDTO>> getAllActiveProducts() {
        return ResponseEntity.ok(productService.getAllActiveProducts());
    }

    @GetMapping("/category/{categoryId}")
    public ResponseEntity<List<ProductDTO>> getActiveProductsByCategory(@PathVariable Long categoryId) {
        return ResponseEntity.ok(productService.getActiveProductsByCategory(categoryId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductDTO> getProductById(@PathVariable Long id) {
        return ResponseEntity.ok(productService.getProductById(id));
    }

    @PostMapping("/variants")
    public ResponseEntity<ProductVariantDTO> createVariant(@Valid @RequestBody ProductVariantCreateDTO dto) {
        ProductVariantDTO created = productService.createVariant(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/variants/{variantId}/stock")
    public ResponseEntity<ProductVariantDTO> updateStock(
            @PathVariable Long variantId,
            @RequestParam Integer newStock) {
        ProductVariantDTO updated = productService.updateStock(variantId, newStock);
        return ResponseEntity.ok(updated);
    }

    @GetMapping("/{productId}/variants")
    public ResponseEntity<List<ProductVariantDTO>> getVariantsByProductId(
            @PathVariable Long productId) {
        return ResponseEntity.ok(productService.getActiveVariantsByProductId(productId));
    }
}
