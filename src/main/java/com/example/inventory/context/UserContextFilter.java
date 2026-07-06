package com.example.inventory.context;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import java.io.IOException;

@Component
public class UserContextFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest httpRequest) {
            String user = httpRequest.getHeader("X-User-Id");
            if (user == null || user.trim().isEmpty()) {
                String authHeader = httpRequest.getHeader("Authorization");
                if (authHeader != null && authHeader.toLowerCase().startsWith("basic ")) {
                    try {
                        String base64Credentials = authHeader.substring(6).trim();
                        byte[] decoded = java.util.Base64.getDecoder().decode(base64Credentials);
                        String credentials = new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
                        int colonIndex = credentials.indexOf(":");
                        if (colonIndex > 0) {
                            user = credentials.substring(0, colonIndex);
                        }
                    } catch (Exception e) {
                        // ignore decode errors
                    }
                }
            }
            if (user != null && !user.trim().isEmpty()) {
                UserContext.setCurrentUser(user.trim());
            } else {
                UserContext.setCurrentUser("anonymous");
            }
        }
        try {
            chain.doFilter(request, response);
        } finally {
            UserContext.clear();
        }
    }
}
