package com.myshop.repository;

import com.myshop.entity.Currency;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CurrencyRepository extends JpaRepository<Currency, UUID> {

    List<Currency> findByEnabledOrderBySortOrderAsc(boolean enabled);
}
