package com.myshop.entity;

import com.myshop.constant.UserRole;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "users")
public class User extends BaseEntity {

    @Column(nullable = false)
    private String username;

    @Column
    private String email;

    /** Mainland China mobile number, stored without the +86 country code. */
    @Column(length = 11)
    private String phone;

    @Column(nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserRole role = UserRole.USER;

    /** Ordinary-member business number. Administrators intentionally have no number. */
    @Column(name = "user_no", updatable = false)
    private Long userNo;

    @Column(name = "user_level_id")
    private UUID userLevelId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal balance = BigDecimal.ZERO;

    private int isDeleted = 0;

    /** 连续登录失败次数 */
    private int failedLoginAttempts = 0;

    /** 账户锁定截止时间（null 表示未锁定） */
    private LocalDateTime lockUntil;
}
