package com.myshop.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.data.domain.Persistable;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Base type for entities whose UUID is assigned by the application before
 * persistence and must therefore remain stable across the whole aggregate.
 *
 * <p>The transient state is deliberately independent from {@link BaseEntity}.
 * Spring Data can then call {@code persist} for a new entity even when its
 * assigned UUID is already present, instead of treating it as a detached
 * entity and issuing an update.</p>
 */
@MappedSuperclass
@Getter
@Setter
public abstract class AssignedUuidEntity implements Persistable<UUID> {

    @Id
    private UUID id;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

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
