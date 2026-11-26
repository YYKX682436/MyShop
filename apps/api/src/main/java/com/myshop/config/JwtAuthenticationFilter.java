package com.myshop.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.common.ApiResponse;
import com.myshop.constant.ErrorCode;
import com.myshop.context.RequestContext;
import com.myshop.entity.User;
import com.myshop.service.AuthCookieService;
import com.myshop.service.AuthSessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/** Resolves persistent opaque sessions and upgrades still-valid legacy JWTs. */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final AuthSessionService sessions;
    private final AuthCookieService cookies;
    private final CustomAccessDeniedHandler accessDeniedHandler;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String token = cookies.getAccessToken(request);
            if (token != null && !token.isBlank()) {
                SecurityContextHolder.clearContext();
                AuthSessionService.Resolution resolution;
                try {
                    resolution = sessions.resolve(token, request, response);
                } catch (RuntimeException exception) {
                    // A database outage is not evidence that the browser
                    // credential expired. Keep cookies intact and let the
                    // client retry the request.
                    log.warn("Authentication session lookup is temporarily unavailable", exception);
                    response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding("UTF-8");
                    response.setHeader("Cache-Control", "no-store");
                    response.getWriter().write(objectMapper.writeValueAsString(
                            ApiResponse.error(ErrorCode.SERVER_ERROR, "认证服务暂不可用，请重试")));
                    return;
                }
                if (resolution.forbidden()) {
                    accessDeniedHandler.handle(request, response, new AccessDeniedException("站点访问受限"));
                    return;
                }
                User user = resolution.user();
                if (user == null) {
                    cookies.clearAuthCookies(response);
                } else {
                    String role = user.getRole().name();
                    RequestContext.set(new RequestContext.UserInfo(user.getId(), user.getUsername(), role));
                    var authentication = new UsernamePasswordAuthenticationToken(user.getId(), null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + role)));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            RequestContext.clear();
        }
    }
}
