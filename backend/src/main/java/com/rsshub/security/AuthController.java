package com.rsshub.security;

import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /me} answers 200 with {@code authenticated=false} rather than 401 on purpose: the SPA router calls it on
 * every cold start to decide whether to show the login screen, and a 401 there would mean an error toast before
 * the user has done anything. Login and logout themselves are handled by the security filters.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final DbUserDetailsService users;

    public AuthController(DbUserDetailsService users) {
        this.users = users;
    }

    @GetMapping("/me")
    public Map<String, Object> me(Authentication authentication) {
        // Anonymous is still an Authentication, so compare the principal rather than isAuthenticated().
        Object principal = authentication == null ? null : authentication.getPrincipal();
        if (!(principal instanceof org.springframework.security.core.userdetails.UserDetails user)) {
            return Map.of("authenticated", false);
        }
        // The login screen needs this before it asks for anything: a session that has a second factor must show a
        // code field, and one that does not must not pretend to.
        boolean totpEnabled = users.find(user.getUsername())
            .map(operator -> operator.totpSecret() != null)
            .orElse(false);
        return Map.of("authenticated", true, "username", user.getUsername(), "totpEnabled", totpEnabled);
    }
}
