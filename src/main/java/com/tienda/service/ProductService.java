package com.tienda.service;

import com.tienda.dto.CategoryCreateDTO;
import com.tienda.dto.CategoryDTO;
import com.tienda.dto.ProductCreateDTO;
import com.tienda.dto.ProductDTO;
import com.tienda.dto.ProductVariantCreateDTO;
import com.tienda.dto.ProductVariantDTO;
import com.tienda.entity.Category;
import com.tienda.entity.Product;
import com.tienda.entity.ProductVariant;
import com.tienda.exception.ResourceNotFoundException;
import com.tienda.repository.CategoryRepository;
import com.tienda.repository.ProductRepository;
import com.tienda.repository.ProductVariantRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final ProductVariantRepository productVariantRepository;

    public CategoryDTO createCategory(CategoryCreateDTO dto) {
        Category category = Category.builder()
                .name(dto.getName())
                .description(dto.getDescription())
                .isActive(true)
                .build();

        return toCategoryDTO(categoryRepository.save(category));
    }

    public List<CategoryDTO> getAllActiveCategories() {
        return categoryRepository.findByIsActiveTrue().stream()
                .map(this::toCategoryDTO)
                .toList();
    }

    public ProductDTO createProduct(ProductCreateDTO dto) {
        Category category = categoryRepository.findById(dto.getCategoryId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Categoría no encontrada con id: " + dto.getCategoryId()));

        Product product = Product.builder()
                .category(category)
                .name(dto.getName())
                .description(dto.getDescription())
                .basePrice(dto.getBasePrice())
                .isActive(true)
                .createdAt(LocalDateTime.now())
                .build();

        return toProductDTO(productRepository.save(product));
    }

    public List<ProductDTO> getActiveProductsByCategory(Long categoryId) {
        return productRepository.findByCategoryIdAndIsActiveTrue(categoryId).stream()
                .map(this::toProductDTO)
                .toList();
    }

    public List<ProductDTO> getAllActiveProducts() {
        return productRepository.findByIsActiveTrueOrderByNameAsc().stream()
                .map(this::toProductDTO)
                .toList();
    }

    public ProductDTO getProductById(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Producto no encontrado con id: " + id));

        return toProductDTO(product);
    }

    public ProductVariantDTO createVariant(ProductVariantCreateDTO dto) {
        productVariantRepository.findBySku(dto.getSku()).ifPresent(existing ->
                { throw new IllegalArgumentException("Ya existe una variante con SKU: " + dto.getSku()); }
        );

        Product product = productRepository.findById(dto.getProductId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Producto no encontrado con id: " + dto.getProductId()));

        ProductVariant variant = ProductVariant.builder()
                .product(product)
                .sku(dto.getSku())
                .size(dto.getSize())
                .color(dto.getColor())
                .stock(dto.getStock())
                .priceOverride(dto.getPriceOverride())
                .isActive(true)
                .build();

        return toProductVariantDTO(productVariantRepository.save(variant));
    }

    @Transactional
    public ProductVariantDTO updateStock(Long variantId, Integer newStock) {
        ProductVariant variant = productVariantRepository.findById(variantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Variante no encontrada con id: " + variantId));

        variant.setStock(newStock);

        return toProductVariantDTO(productVariantRepository.save(variant));
    }

    public List<ProductVariantDTO> getActiveVariantsByProductId(Long productId) {
        return productVariantRepository.findByProductIdAndIsActiveTrue(productId).stream()
                .map(this::toProductVariantDTO)
                .toList();
    }

    private CategoryDTO toCategoryDTO(Category category) {
        return CategoryDTO.builder()
                .id(category.getId())
                .name(category.getName())
                .description(category.getDescription())
                .isActive(category.getIsActive())
                .build();
    }

    private ProductDTO toProductDTO(Product product) {
        return ProductDTO.builder()
                .id(product.getId())
                .categoryId(product.getCategory().getId())
                .categoryName(product.getCategory().getName())
                .name(product.getName())
                .description(product.getDescription())
                .basePrice(product.getBasePrice())
                .isActive(product.getIsActive())
                .createdAt(product.getCreatedAt())
                .build();
    }

    private ProductVariantDTO toProductVariantDTO(ProductVariant variant) {
        return ProductVariantDTO.builder()
                .id(variant.getId())
                .productId(variant.getProduct().getId())
                .sku(variant.getSku())
                .size(variant.getSize())
                .color(variant.getColor())
                .stock(variant.getStock())
                .priceOverride(variant.getPriceOverride())
                .isActive(variant.getIsActive())
                .build();
    }
}
