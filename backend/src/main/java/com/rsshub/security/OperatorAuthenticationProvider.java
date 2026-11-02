package com.rsshub.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.security.authentication.AuthenticationDetailsSource;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The operator's credential check: lockout, then password, then the TOTP step 03 §9.1 asks for.
 *
 * <p>It <em>replaces</em> the auto-configured DAO provider rather than sitting next to it, because when two
 * providers are on one manager the first "yes" wins and the plain DAO provider knows nothing about second factors —
 * a co-installed one would be a way to log in with a password alone.
 */
class OperatorAuthenticationProvider implements AuthenticationProvider {

    /** The login form may send a code alongside the password; as a field or as a header, whichever the client has. */
    record LoginDetails(String totpCode) implements java.io.Serializable {
    }

    static final class LoginDetailsSource implements AuthenticationDetailsSource<HttpServletRequest, LoginDetails> {
        @Override
        public LoginDetails buildDetails(HttpServletRequest request) {
            String code = request.getParameter("code");
            if (code == null) {
                code = request.getHeader("X-TOTP-Code");
            }
            return new LoginDetails(code == null || code.isBlank() ? null : code.trim());
        }
    }

    /**
     * A principal with no password to carry. {@code User.builder()} refuses a null credential, and putting the real
     * hash in the session so it can be "erased" later is a worse trade than four extra lines here.
     */
    record OperatorPrincipal(String username) implements UserDetails {

        private static final List<GrantedAuthority> AUTHORITIES =
            List.of(new SimpleGrantedAuthority("ROLE_OPERATOR"));

        @Override
        public java.util.Collection<? extends GrantedAuthority> getAuthorities() {
            return AUTHORITIES;
        }

        @Override
        public String getPassword() {
            return null;
        }

        @Override
        public String getUsername() {
            return username;
        }

        @Override
        public boolean isAccountNonExpired() {
            return true;
        }

        @Override
        public boolean isAccountNonLocked() {
            return true;
        }

        @Override
        public boolean isCredentialsNonExpired() {
            return true;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }
    }

    private final DbUserDetailsService users;
    private final PasswordEncoder passwords;
    private final SecretCipher cipher;
    private final LoginAttemptGuard guard;
    /** Burned on every unknown username so "no such user" and "wrong password" cost the same time. */
    private final String dummyHash;

    OperatorAuthenticationProvider(DbUserDetailsService users, PasswordEncoder passwords, SecretCipher cipher,
        LoginAttemptGuard guard) {
        this.users = users;
        this.passwords = passwords;
        this.cipher = cipher;
        this.guard = guard;
        this.dummyHash = passwords.encode(java.util.UUID.randomUUID().toString());
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String username = authentication.getName();
        String password = authentication.getCredentials() == null ? "" : String.valueOf(authentication.getCredentials());
        String code = authentication.getDetails() instanceof LoginDetails details ? details.totpCode() : null;

        if (guard.locked(username)) {
            throw new LockedException("Too many failed attempts; the account is locked for now");
        }

        // Optional rather than an exception, so an unknown name is answered with a fake compare and the same text a
        // wrong password gets.
        var account = users.find(username);
        if (account.isEmpty()) {
            passwords.matches(password, dummyHash);
            guard.recordFailure(username);
            throw new BadCredentialsException("Invalid username or password");
        }
        if (!passwords.matches(password, account.get().passwordHash())) {
            guard.recordFailure(username);
            throw new BadCredentialsException("Invalid username or password");
        }

        String secret = cipher.decrypt(account.get().totpSecret());
        if (secret != null && (code == null
            || !TotpService.matches(secret, code, java.time.Instant.now().getEpochSecond()))) {
            guard.recordFailure(username);
            throw new BadCredentialsException("Invalid second-factor code");
        }

        guard.recordSuccess(username);
        UsernamePasswordAuthenticationToken result = new UsernamePasswordAuthenticationToken(
            new OperatorPrincipal(account.get().username()), null,
            List.of(new SimpleGrantedAuthority("ROLE_OPERATOR")));
        result.setDetails(authentication.getDetails());
        return result;
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
