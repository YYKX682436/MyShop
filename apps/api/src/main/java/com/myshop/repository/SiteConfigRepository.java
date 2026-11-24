package com.myshop.repository;

import com.myshop.entity.SiteConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SiteConfigRepository extends JpaRepository<SiteConfig, UUID> {

    Optional<SiteConfig> findByConfigKey(String configKey);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select c from SiteConfig c where c.configKey = :key")
    Optional<SiteConfig> findByConfigKeyForUpdate(@org.springframework.data.repository.query.Param("key") String configKey);

    List<SiteConfig> findByConfigGroup(String configGroup);
}
