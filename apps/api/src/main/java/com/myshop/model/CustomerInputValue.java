package com.myshop.model;

/**
 * A normalized customer value. Order items store this complete shape as a
 * historical snapshot, including the label that was visible at checkout.
 */
public record CustomerInputValue(String id, String label, String value) {
}
