package com.myshop.model.response;

import com.myshop.entity.User;
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
    private String role;
    private Long userNo;
    private BigDecimal balance;
    /** True for both administrator roles; the UI renders the balance as ∞. */
    private boolean unlimited;
    private LocalDateTime createdAt;

    public static UserProfileResponse from(User user) {
        UserProfileResponse r = new UserProfileResponse();
        r.setId(user.getId());
        r.setUsername(user.getUsername());
        r.setEmail(user.getEmail());
        r.setRole(user.getRole().name());
        r.setUserNo(user.getUserNo());
        r.setBalance(user.getBalance());
        r.setUnlimited(user.getRole() == com.myshop.constant.UserRole.ADMIN
                || user.getRole() == com.myshop.constant.UserRole.SUPER_ADMIN);
        r.setCreatedAt(user.getCreatedAt());
        return r;
    }
}
