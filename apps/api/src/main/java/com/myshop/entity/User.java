package com.myshop.entity;

import com.myshop.constant.UserRole;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "users")
public class User extends BaseEntity {

    @Column(unique = true, nullable = false)
    private String username;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserRole role = UserRole.USER;

    /** Ordinary-member business number. Administrators intentionally have no number. */
    @Column(name = "user_no", updatable = false)
    private Long userNo;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal balance = BigDecimal.ZERO;

    private int isDeleted = 0;

    /** 连续登录失败次数 */
    private int failedLoginAttempts = 0;

    /** 账户锁定截止时间（null 表示未锁定） */
    private LocalDateTime lockUntil;
}
