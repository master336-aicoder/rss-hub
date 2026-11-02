package com.rsshub.adminapi;

import com.rsshub.security.ApiTokenService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/tokens} — the operator's view of the machine credentials (03 §9.1).
 *
 * <p>Creation returns the secret in the response body and never again: the row keeps only its SHA-256, so a later
 * GET can list and revoke but cannot reveal. That asymmetry is the whole design, and it is why the response type of
 * {@code create} differs from {@code list}.
 */
@RestController
@RequestMapping("/api/tokens")
public class TokenController {

    public record CreateRequest(@NotBlank String name,
        @NotBlank @Pattern(regexp = "ingest|read|admin") String scope) {
    }

    /** {@code secret} is present exactly once, in the response to POST. */
    public record CreatedView(long id, String name, String scope, String secret) {
    }

    private final ApiTokenService tokens;

    public TokenController(ApiTokenService tokens) {
        this.tokens = tokens;
    }

    @GetMapping
    public List<ApiTokenService.Token> list() {
        return tokens.list();
    }

    @PostMapping
    public CreatedView create(@RequestBody CreateRequest request) {
        ApiTokenService.Created created = tokens.create(request.name(), request.scope());
        return new CreatedView(created.id(), created.name(), created.scope(), created.secret());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable long id) {
        tokens.revoke(id);
    }
}
