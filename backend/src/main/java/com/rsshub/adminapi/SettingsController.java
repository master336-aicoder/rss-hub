package com.rsshub.adminapi;

import com.rsshub.settings.SettingsService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The settings page's backing endpoint. GET always returns the full resolved view — there is no per-key read to
 * keep in sync with the page — and PATCH is partial, so saving one knob never rewrites its neighbours.
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SettingsService settings;

    public SettingsController(SettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    public SettingsService.View get() {
        return settings.view();
    }

    @PatchMapping
    public SettingsService.View update(@RequestBody Map<String, Object> patch) {
        return settings.update(patch);
    }
}
