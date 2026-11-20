package com.myshop.supplier;

import java.util.List;

/** Read-only provider boundary. Funds and fulfillment are intentionally outside P1. */
public interface SupplierProvider {
    String type();
    SupplierModels.Account account(SupplierModels.Connection connection);
    List<SupplierModels.Category> categories(SupplierModels.Connection connection);
    SupplierModels.Page list(SupplierModels.Connection connection, String keyword, String categoryId, int page);
    SupplierModels.Product detail(SupplierModels.Connection connection, String goodsId);
}
