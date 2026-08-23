package com.myshop.config;

import com.myshop.common.ApiResponse;
import com.myshop.constant.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/** Double-submit protection for browser requests authenticated by a cookie. */
@Component
@Order(1)
@RequiredArgsConstructor
public class CsrfCookieFilter extends OncePerRequestFilter {

    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isMutating(request) && hasCookie(request, "myshop_access_token")) {
            String cookieToken = cookieValue(request, "myshop_csrf");
            String headerToken = request.getHeader("X-CSRF-Token");
            if (!StringUtils.hasText(cookieToken) || !StringUtils.hasText(headerToken)
                    || !MessageDigest.isEqual(cookieToken.getBytes(StandardCharsets.UTF_8), headerToken.getBytes(StandardCharsets.UTF_8))) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json");
                response.setCharacterEncoding("UTF-8");
                response.getWriter().write(objectMapper.writeValueAsString(
                        ApiResponse.error(ErrorCode.FORBIDDEN, "CSRF 校验失败")));
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private boolean isMutating(HttpServletRequest request) {
        return switch (request.getMethod().toUpperCase()) {
            case "POST", "PUT", "PATCH", "DELETE" -> !request.getRequestURI().endsWith("/auth/login")
                    && !request.getRequestURI().endsWith("/auth/register");
            default -> false;
        };
    }

    private boolean hasCookie(HttpServletRequest request, String name) {
        return cookieValue(request, name) != null;
    }

    private String cookieValue(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) if (name.equals(cookie.getName())) return cookie.getValue();
        return null;
    }
}
