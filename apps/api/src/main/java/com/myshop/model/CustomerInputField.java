package com.myshop.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * A customer-input definition owned by a product. The id is deliberately
 * independent from the label so renaming a field does not invalidate values
 * already present in an order.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CustomerInputField(String id, String label, Type type, Boolean required,
                                 String placeholder, String regex, List<Option> options) {
    public CustomerInputField(String id, String label) { this(id,label,null,null,null,null,null); }
    public enum Type { TEXT, IMAGE, NUMBER, SELECT, MULTILINE, CASCADE }
    public record Option(String name,String value) {}
}
