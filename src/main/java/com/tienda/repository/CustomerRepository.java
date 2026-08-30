package com.tienda.repository;

import com.tienda.entity.Customer;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

    Optional<Customer> findByTelegramChatId(Long telegramChatId);
}
