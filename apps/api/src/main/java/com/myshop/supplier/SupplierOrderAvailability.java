package com.myshop.supplier;

import com.myshop.constant.StockMode;
import com.myshop.entity.OrderItem;
import com.myshop.entity.SupplierPurchase;
import com.myshop.repository.SupplierAccountRepository;
import com.myshop.repository.SupplierPurchaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/** The enabled supplier account is the sales and procurement boundary for supplier order intents. */
@Service
@RequiredArgsConstructor
public class SupplierOrderAvailability {
    private final SupplierPurchaseRepository purchases;
    private final SupplierAccountRepository accounts;

    public boolean allowsPayment(UUID orderId, List<OrderItem> items) {
        List<OrderItem> supplierItems = items.stream()
                .filter(item -> item.getStockMode() == StockMode.SUPPLIER)
                .toList();
        if (supplierItems.isEmpty()) return true;

        List<SupplierPurchase> intents = purchases.findByOrderId(orderId);
        if (intents.size() != supplierItems.size()) return false;
        for (OrderItem item : supplierItems) {
            var intent = intents.stream().filter(row -> row.getOrderItemId().equals(item.getId())).findFirst();
            if (intent.isEmpty()) return false;
            if (accounts.findById(intent.get().getAccountId()).filter(account -> account.isEnabled()).isEmpty()) return false;
        }
        return true;
    }
}
