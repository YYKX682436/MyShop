package com.myshop.entity;

import com.myshop.constant.AfterSaleMessageSenderType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.Persistable;
import java.time.LocalDateTime;
import java.util.UUID;

/** One immutable message in an after-sale request conversation. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "after_sale_messages")
public class AfterSaleMessage implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "request_id", nullable = false)
    private UUID requestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "sender_type", nullable = false, length = 16)
    private AfterSaleMessageSenderType senderType;

    @Column(name = "sender_user_id", nullable = false)
    private UUID senderUserId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Transient
    private boolean isNew = true;

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    protected void markNotNew() {
        isNew = false;
    }
}
