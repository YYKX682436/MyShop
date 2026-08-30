package com.myshop.model;

/**
 * A normalized customer value. Order items store this complete shape as a
 * historical snapshot, including the label that was visible when the order was created.
 */
public record CustomerInputValue(String id, String label, String value) {
}
