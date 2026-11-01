package com.example.flashsale.repository;

import com.example.flashsale.domain.Order;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByRequestId(String requestId);
}
