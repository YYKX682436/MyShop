package com.myshop.supplier;

import com.myshop.entity.SupplierDeliveryCard;
import java.util.ArrayList;
import java.util.List;

/** Keeps administrator and customer delivery formatting identical. */
public final class SupplierCardContentFormatter {
    private SupplierCardContentFormatter() { }

    public static List<String> parts(SupplierDeliveryCard card) {
        return parts(card.getCardNo(),card.getCardPwd());
    }

    public static List<String> parts(String cardNo,String cardPwd) {
        List<String> parts=new ArrayList<>(2);
        if (cardNo!=null && !cardNo.isBlank()) parts.add(cardNo);
        if (cardPwd!=null && !cardPwd.isBlank()) parts.add(cardPwd);
        return List.copyOf(parts);
    }
}
