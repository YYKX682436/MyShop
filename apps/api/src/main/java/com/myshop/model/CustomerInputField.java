package com.myshop.model;

/**
 * A customer-input definition owned by a product. The id is deliberately
 * independent from the label so renaming a field does not invalidate values
 * already present in an order.
 */
public record CustomerInputField(String id, String label) {
}
