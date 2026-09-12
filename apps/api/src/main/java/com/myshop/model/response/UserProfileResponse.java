package com.myshop.model.response;

import com.myshop.entity.User;
import com.myshop.entity.UserLevel;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class UserProfileResponse {
    private UUID id;
    private String username;
    private String email;
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private String avatarUrl;
    private String role;
    private Long userNo;
    private UUID userLevelId;
    private String userLevelName;
    private BigDecimal balance;
    /** True for both administrator roles; the UI renders the balance as ∞. */
    private boolean unlimited;
    private LocalDateTime createdAt;

    public static UserProfileResponse from(User user) {
        return from(user, null);
    }

    public static UserProfileResponse from(User user, UserLevel level) {
        UserProfileResponse r = new UserProfileResponse();
        r.setId(user.getId());
        r.setUsername(user.getUsername());
        r.setEmail(user.getEmail());
        r.setAvatarUrl(user.getAvatarUrl());
        r.setRole(user.getRole().name());
        r.setUserNo(user.getUserNo());
        r.setUserLevelId(user.getUserLevelId());
        r.setUserLevelName(level == null ? null : level.getName());
        r.setBalance(user.getBalance());
        r.setUnlimited(user.getRole() == com.myshop.constant.UserRole.ADMIN
                || user.getRole() == com.myshop.constant.UserRole.SUPER_ADMIN);
        r.setCreatedAt(user.getCreatedAt());
        return r;
    }
}
