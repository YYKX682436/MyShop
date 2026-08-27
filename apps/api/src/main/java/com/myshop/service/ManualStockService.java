package com.myshop.service;

import com.myshop.constant.ErrorCode;
import com.myshop.entity.ManualStock;
import com.myshop.exception.BusinessException;
import com.myshop.repository.ManualStockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Transactional operations for finite manual inventory pools. */
@Service
@RequiredArgsConstructor
public class ManualStockService {

    private final ManualStockRepository manualStockRepository;

    @Transactional
    public void ensurePool(UUID productId, UUID specId) {
        manualStockRepository.ensurePool(UUID.randomUUID(), productId, specId);
    }

    @Transactional(readOnly = true)
    public int getAvailable(UUID productId, UUID specId) {
        return manualStockRepository.findPool(productId, specId)
                .map(ManualStock::getAvailableQuantity)
                .orElse(0);
    }

    @Transactional(readOnly = true)
    public int getReserved(UUID productId, UUID specId) {
        return manualStockRepository.findPool(productId, specId)
                .map(ManualStock::getReservedQuantity)
                .orElse(0);
    }

    @Transactional
    public void setAvailable(UUID productId, UUID specId, int availableQuantity) {
        if (availableQuantity < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "手动库存不能小于 0");
        }
        ensurePool(productId, specId);
        manualStockRepository.setAvailable(productId, specId, availableQuantity);
    }

    @Transactional
    public void reserveOrThrow(UUID productId, UUID specId, int quantity) {
        if (quantity < 1) return;
        ensurePool(productId, specId);
        if (manualStockRepository.reserve(productId, specId, quantity) != 1) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK, "人工库存不足",
                    java.util.Map.of("available", getAvailable(productId, specId)));
        }
    }

    @Transactional
    public void release(UUID productId, UUID specId, int quantity) {
        if (quantity > 0) {
            if (manualStockRepository.release(productId, specId, quantity) != 1) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "人工库存预占恢复状态异常，请联系管理员");
            }
        }
    }

    @Transactional
    public void consumeReservation(UUID productId, UUID specId, int quantity) {
        if (quantity < 1) return;
        if (manualStockRepository.consumeReservation(productId, specId, quantity) != 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "人工库存预占状态异常，请联系管理员");
        }
    }
}
