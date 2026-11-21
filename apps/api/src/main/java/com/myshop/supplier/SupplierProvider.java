package com.myshop.supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.myshop.model.CustomerInputField;
import java.util.List;

/** Provider boundary used by the catalog and purchase workflow. */
public interface SupplierProvider {
    String type();
    SupplierModels.Account account(SupplierModels.Connection connection);
    List<SupplierModels.Category> categories(SupplierModels.Connection connection);
    SupplierModels.Page list(SupplierModels.Connection connection, String keyword, String categoryId, int page);
    SupplierModels.Product detail(SupplierModels.Connection connection, String goodsId);
    List<CustomerInputField> customerFields(JsonNode catalogSnapshot);
    SupplierModels.CreatedPurchase createPurchase(SupplierModels.Connection connection, SupplierModels.PurchaseRequest request);
    SupplierModels.PurchaseDetail purchaseDetail(SupplierModels.Connection connection, String outerNumber);
    default SupplierModels.RefundResult requestRefund(SupplierModels.Connection connection,String outerNumber) {
        throw new SupplierException("UNSUPPORTED","该供应商不支持退款申请");
    }
    default SupplierModels.ComplaintResult createComplaint(SupplierModels.Connection connection,SupplierModels.ComplaintRequest request) {
        throw new SupplierException("UNSUPPORTED","该供应商不支持售后申诉");
    }
    default SupplierModels.CardDetail cardDetail(SupplierModels.Connection connection,String cardId) {
        throw new SupplierException("UNSUPPORTED","该供应商不支持卡券查询");
    }
    default SupplierModels.CardVoidResult voidCard(SupplierModels.Connection connection,String cardId) {
        throw new SupplierException("UNSUPPORTED","该供应商不支持卡券作废");
    }
}
