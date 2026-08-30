package com.tienda.repository;

import com.tienda.entity.Order;
import com.tienda.entity.OrderStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {

    List<Order> findByCustomerIdAndStatus(Long customerId, OrderStatus status);

    List<Order> findAllByOrderByCreatedAtDesc();
}
