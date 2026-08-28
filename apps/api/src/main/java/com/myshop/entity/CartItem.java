package com.myshop.entity;

import com.myshop.model.CustomerInputValue;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "cart_items")
public class CartItem extends BaseEntity {

    private UUID userId;

    private String sessionToken;

    @Column(nullable = false)
    private UUID productId;

    private UUID specId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "customer_inputs", nullable = false, columnDefinition = "jsonb")
    private List<CustomerInputValue> customerInputs = new ArrayList<>();

    @Column(nullable = false)
    private int quantity;
}
