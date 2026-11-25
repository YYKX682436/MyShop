package com.myshop.config;

import com.myshop.context.RequestContext;
import com.myshop.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** A client assertion for detecting cookie changes; never used to grant access. */
public class MessageIdentityFilter extends OncePerRequestFilter {
    private final UserRepository users;

    public MessageIdentityFilter(UserRepository users) { this.users = users; }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getServletPath();
        boolean messagePath = path.startsWith("/support/") || path.startsWith("/admin/support/")
                || path.equals("/announcements") || path.startsWith("/announcements/");
        if (messagePath) {
            response.setHeader("Cache-Control", "no-store, private, max-age=0");
            response.setHeader("Pragma", "no-cache");
        }
        String expected = request.getHeader("X-Message-Viewer");
        if (messagePath && expected != null) {
            String actual = RequestContext.getUserId() == null ? "guest" : "user:" + RequestContext.getUserId();
            if (!actual.equals(expected)) {
                response.setStatus(409); response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":10003,\"message\":\"身份已变化，请刷新后重试\",\"data\":null}");
                return;
            }
        }
        String expectedLevel = request.getHeader("X-Message-Level");
        if (messagePath && expectedLevel != null && RequestContext.getUserId() != null) {
            String actualLevel = users.findById(RequestContext.getUserId())
                    .map(user -> user.getUserLevelId() == null ? "none" : user.getUserLevelId().toString())
                    .orElse("missing");
            if (!expectedLevel.equals(actualLevel)) {
                response.setStatus(409); response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":10003,\"message\":\"账户等级已变化，请刷新后重试\",\"data\":null}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
