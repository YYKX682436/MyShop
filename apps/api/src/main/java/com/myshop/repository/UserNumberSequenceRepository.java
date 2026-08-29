package com.myshop.repository;

import com.myshop.entity.UserNumberSequence;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserNumberSequenceRepository extends JpaRepository<UserNumberSequence, Integer> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM UserNumberSequence s WHERE s.id = :id")
    UserNumberSequence findByIdForUpdate(@Param("id") Integer id);
}
