package com.tienda.service;

import com.tienda.dto.OrderDTO;
import com.tienda.dto.OrderItemDTO;
import com.tienda.dto.OrderItemRequestDTO;
import com.tienda.entity.Customer;
import com.tienda.entity.Order;
import com.tienda.entity.OrderItem;
import com.tienda.entity.OrderStatus;
import com.tienda.entity.ProductVariant;
import com.tienda.exception.InsufficientStockException;
import com.tienda.exception.PaymentAlreadyProcessedException;
import com.tienda.exception.ResourceNotFoundException;
import com.tienda.repository.CustomerRepository;
import com.tienda.repository.OrderItemRepository;
import com.tienda.repository.OrderRepository;
import com.tienda.repository.ProductVariantRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(rollbackFor = Exception.class)
public class OrderService {

    private final CustomerRepository customerRepository;
    private final ProductVariantRepository productVariantRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;

    public OrderDTO createOrder(Long customerId, List<OrderItemRequestDTO> itemsRequest) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Cliente no encontrado con id: " + customerId));

        List<OrderItem> orderItems = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;

        for (OrderItemRequestDTO itemRequest : itemsRequest) {
            ProductVariant variant = productVariantRepository.findById(itemRequest.getProductVariantId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Variante no encontrada con id: " + itemRequest.getProductVariantId()));

            int quantityRequested = itemRequest.getQuantity();

            if (variant.getStock() < quantityRequested) {
                throw new InsufficientStockException(
                        "Stock insuficiente para SKU: " + variant.getSku());
            }

            variant.setStock(variant.getStock() - quantityRequested);
            productVariantRepository.save(variant);

            BigDecimal unitPrice = resolveUnitPrice(variant);
            totalAmount = totalAmount.add(unitPrice.multiply(BigDecimal.valueOf(quantityRequested)));

            orderItems.add(OrderItem.builder()
                    .productVariant(variant)
                    .quantity(quantityRequested)
                    .unitPrice(unitPrice)
                    .build());
        }

        Order order = Order.builder()
                .customer(customer)
                .totalAmount(totalAmount)
                .status(OrderStatus.PENDING)
                .build();

        Order savedOrder = orderRepository.save(order);

        List<OrderItem> savedItems = new ArrayList<>();
        for (OrderItem orderItem : orderItems) {
            orderItem.setOrder(savedOrder);
            savedItems.add(orderItemRepository.save(orderItem));
        }

        return toOrderDTO(savedOrder, savedItems);
    }

    public List<OrderDTO> getAllOrders() {
        return orderRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(order -> toOrderDTO(order, orderItemRepository.findByOrderId(order.getId())))
                .toList();
    }

    public OrderDTO updateOrderStatus(Long orderId, OrderStatus newStatus) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Orden no encontrada con id: " + orderId));

        OrderStatus previousStatus = order.getStatus();

        if (newStatus == OrderStatus.CANCELLED && previousStatus != OrderStatus.CANCELLED) {
            List<OrderItem> items = orderItemRepository.findByOrderId(orderId);

            for (OrderItem item : items) {
                ProductVariant variant = item.getProductVariant();
                variant.setStock(variant.getStock() + item.getQuantity());
                productVariantRepository.save(variant);
            }
        }

        order.setStatus(newStatus);
        Order updatedOrder = orderRepository.save(order);

        return toOrderDTO(updatedOrder, orderItemRepository.findByOrderId(orderId));
    }

    @Transactional
    public OrderDTO confirmOrderAfterPayment(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Orden no encontrada con id: " + orderId));

        if (order.getStatus() != OrderStatus.PENDING) {
            throw new PaymentAlreadyProcessedException(
                    "La orden " + orderId + " no está pendiente de pago. Estado actual: " + order.getStatus());
        }

        List<OrderItem> items = orderItemRepository.findByOrderId(orderId);
        validateOrderItemsStock(items);

        order.setStatus(OrderStatus.CONFIRMED);
        Order confirmedOrder = orderRepository.save(order);

        return toOrderDTO(confirmedOrder, items);
    }

    private void validateOrderItemsStock(List<OrderItem> items) {
        for (OrderItem item : items) {
            ProductVariant variant = item.getProductVariant();

            if (!Boolean.TRUE.equals(variant.getIsActive())) {
                throw new InsufficientStockException(
                        "Variante inactiva o no disponible: " + variant.getSku());
            }

            if (variant.getStock() < 0) {
                throw new InsufficientStockException(
                        "Stock inválido para SKU: " + variant.getSku());
            }
        }
    }

    private BigDecimal resolveUnitPrice(ProductVariant variant) {
        if (variant.getPriceOverride() != null) {
            return variant.getPriceOverride();
        }
        return variant.getProduct().getBasePrice();
    }

    private OrderDTO toOrderDTO(Order order, List<OrderItem> items) {
        return OrderDTO.builder()
                .id(order.getId())
                .customerId(order.getCustomer().getId())
                .customerName(order.getCustomer().getFullName())
                .totalAmount(order.getTotalAmount())
                .status(order.getStatus())
                .createdAt(order.getCreatedAt())
                .items(items.stream().map(this::toOrderItemDTO).toList())
                .build();
    }

    private OrderItemDTO toOrderItemDTO(OrderItem item) {
        ProductVariant variant = item.getProductVariant();

        return OrderItemDTO.builder()
                .id(item.getId())
                .productVariantId(variant.getId())
                .productName(variant.getProduct().getName())
                .variantSku(variant.getSku())
                .quantity(item.getQuantity())
                .unitPrice(item.getUnitPrice())
                .build();
    }
}
