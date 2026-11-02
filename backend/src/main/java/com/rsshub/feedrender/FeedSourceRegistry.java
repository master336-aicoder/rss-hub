package com.rsshub.feedrender;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Routes a {@link FeedKey} to the {@link FeedSource} that owns its scope.
 *
 * <p>This indirection is what keeps {@code feedrender} free of {@code editorial}: the controller depends on the SPI
 * and Spring hands it the beans, so adding the event source in M3 or the podcast in M4 adds a bean and changes no
 * rendering code (03 §6.1's "四种发布形态共用一个渲染层").
 */
@Component
public class FeedSourceRegistry {

    private final Map<FeedScope, FeedSource> byScope = new EnumMap<>(FeedScope.class);

    public FeedSourceRegistry(List<FeedSource> sources) {
        for (FeedSource source : sources) {
            FeedSource previous = byScope.put(source.scope(), source);
            if (previous != null) {
                throw new IllegalStateException("two feed sources for scope " + source.scope()
                    + ": " + previous.getClass().getName() + " and " + source.getClass().getName());
            }
        }
    }

    /** Empty means "this site does not publish that outlet", which the controller answers with 404. */
    public Optional<FeedSource> find(FeedKey key) {
        return Optional.ofNullable(byScope.get(key.scope()));
    }

    public boolean publishes(FeedScope scope) {
        return byScope.containsKey(scope);
    }
}
