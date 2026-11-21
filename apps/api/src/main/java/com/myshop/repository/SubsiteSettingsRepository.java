package com.myshop.repository;

import com.myshop.entity.SubsiteSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface SubsiteSettingsRepository extends JpaRepository<SubsiteSettings, Integer> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SubsiteSettings s where s.id = 1")
    Optional<SubsiteSettings> lockSettings();
}
