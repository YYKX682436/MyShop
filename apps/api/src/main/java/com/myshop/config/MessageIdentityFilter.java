package com.myshop.config;

import com.myshop.context.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** A client assertion for detecting cookie changes; never used to grant access. */
public class MessageIdentityFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getServletPath();
        boolean messagePath = path.startsWith("/support/") || path.startsWith("/admin/support/")
                || path.equals("/announcements") || path.startsWith("/announcements/");
        String expected = request.getHeader("X-Message-Viewer");
        if (messagePath && expected != null) {
            String actual = RequestContext.getUserId() == null ? "guest" : "user:" + RequestContext.getUserId();
            if (!actual.equals(expected)) {
                response.setStatus(409); response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":10003,\"message\":\"身份已变化，请刷新后重试\",\"data\":null}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
