package com.tienda.repository;

import com.tienda.entity.Product;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, Long> {

    List<Product> findByCategoryIdAndIsActiveTrue(Long categoryId);

    List<Product> findByIsActiveTrueOrderByNameAsc();
}
