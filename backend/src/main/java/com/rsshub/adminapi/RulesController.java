package com.rsshub.adminapi;

import com.rsshub.rules.RuleService;
import com.rsshub.rules.RuleService.Preview;
import com.rsshub.rules.RuleService.Request;
import com.rsshub.rules.RuleService.View;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 03 §10's {@code /api/rules}. The four rule columns are JSON documents and travel as decoded nodes, so the SPA can
 * render the editor without a second parser — and a malformed one is a 400 from {@link RuleService} on the way in,
 * never a rule that silently stops firing.
 */
@RestController
@RequestMapping("/api/rules")
public class RulesController {

    public record EnabledRequest(boolean enabled) {
    }

    private final RuleService rules;

    public RulesController(RuleService rules) {
        this.rules = rules;
    }

    @GetMapping
    public List<View> list() {
        return rules.list();
    }

    @GetMapping("/{id}")
    public View get(@PathVariable long id) {
        return rules.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public View create(@RequestBody Request request) {
        return rules.create(request);
    }

    /** Partial by design: an absent column keeps what is stored, including the enabled flag. */
    @PatchMapping("/{id}")
    public View update(@PathVariable long id, @RequestBody Request request) {
        return rules.update(id, request);
    }

    /**
     * The only way to re-arm a rule the circuit breaker stopped. Kept off the PATCH so clearing
     * {@code disabled_reason} is an explicit act the operator can point at in the UI.
     */
    @PatchMapping("/{id}/enabled")
    public View setEnabled(@PathVariable long id, @RequestBody EnabledRequest request) {
        return rules.setEnabled(id, request.enabled());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        rules.delete(id);
    }

    /**
     * FR-5.4's test button. Nothing is written and nothing is sent: the response is only what the last entries would
     * have matched, which is what makes it safe to press against a live rule.
     */
    @PostMapping("/{id}/test")
    public Preview test(@PathVariable long id, @RequestParam(defaultValue = "200") int limit) {
        return rules.preview(id, limit);
    }
}
