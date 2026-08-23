package com.myshop.repository;

import com.myshop.entity.VisitStats;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface VisitStatsRepository extends JpaRepository<VisitStats, UUID> {

    Optional<VisitStats> findByVisitDate(LocalDate visitDate);
}
