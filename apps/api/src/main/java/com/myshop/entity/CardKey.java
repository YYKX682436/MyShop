package com.myshop.entity;

import com.myshop.constant.CardKeyStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "card_keys")
public class CardKey extends BaseEntity {

    @Column(nullable = false)
    private UUID productId;

    private UUID specId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content_parts", nullable = false, columnDefinition = "jsonb")
    private List<String> contentParts = new ArrayList<>();

    @Column(name = "content_fingerprint", nullable = false, length = 64)
    private String contentFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CardKeyStatus status = CardKeyStatus.AVAILABLE;

    private UUID orderId;

    private UUID orderItemId;

    private UUID importBatchId;

    private LocalDateTime soldAt;

}
