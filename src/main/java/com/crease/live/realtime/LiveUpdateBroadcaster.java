package com.crease.live.realtime;

import com.crease.live.domain.Match;
import com.crease.live.repository.MatchRepository;
import com.crease.live.service.ScorecardAssembler;
import com.crease.live.view.Views.Scorecard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Server-Sent Events hub. Two kinds of subscription:
 * <ul>
 *   <li>the global feed ({@code /api/stream}) receives a compact {@code card} per update, for match lists;</li>
 *   <li>a match stream ({@code /api/matches/{id}/stream}) receives the full {@code scorecard} plus each {@code ball}.</li>
 * </ul>
 * Fan-out runs on a dedicated executor so scoring threads never wait on slow clients; the
 * snapshot is built once per version and shared by every subscriber.
 */
@Component
public class LiveUpdateBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(LiveUpdateBroadcaster.class);
    private static final long STREAM_TIMEOUT_MS = 30 * 60 * 1000L;   // clients auto-reconnect

    private final MatchRepository matches;
    private final ScorecardAssembler assembler;
    private final Set<SseEmitter> feed = new CopyOnWriteArraySet<>();
    private final Map<Long, Set<SseEmitter>> perMatch = new ConcurrentHashMap<>();

    public LiveUpdateBroadcaster(MatchRepository matches, ScorecardAssembler assembler) {
        this.matches = matches;
        this.assembler = assembler;
    }

    public SseEmitter subscribeFeed() {
        SseEmitter emitter = register(feed);
        send(emitter, "hello", Map.of("subscribers", subscriberCount()));
        return emitter;
    }

    public SseEmitter subscribeMatch(Match m) {
        SseEmitter emitter = register(perMatch.computeIfAbsent(m.getId(), k -> new CopyOnWriteArraySet<>()));
        send(emitter, "scorecard", assembler.snapshot(m));
        return emitter;
    }

    public int subscriberCount() {
        return feed.size() + perMatch.values().stream().mapToInt(Set::size).sum();
    }

    @Async("broadcastExecutor")
    @EventListener
    public void onMatchUpdated(MatchUpdatedEvent e) {
        if (e.removed()) {
            feed.forEach(em -> send(em, "removed", Map.of("id", e.matchId())));
            Set<SseEmitter> subs = perMatch.remove(e.matchId());
            if (subs != null) subs.forEach(SseEmitter::complete);
            return;
        }
        Match m = matches.findById(e.matchId()).orElse(null);
        if (m == null) return;
        Scorecard sc = assembler.snapshot(m);
        if (!feed.isEmpty()) {
            Object card = sc.toCard();
            feed.forEach(em -> send(em, "card", card));
        }
        Set<SseEmitter> subs = perMatch.get(e.matchId());
        if (subs != null && !subs.isEmpty()) {
            subs.forEach(em -> {
                if (e.ball() != null) send(em, "ball", e.ball());
                send(em, "scorecard", sc);
            });
        }
    }

    /** Keeps proxies from closing idle connections and evicts dead subscribers. */
    @Scheduled(fixedRate = 15_000)
    public void heartbeat() {
        feed.forEach(em -> ping(em));
        perMatch.values().forEach(set -> set.forEach(this::ping));
    }

    private SseEmitter register(Set<SseEmitter> group) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        group.add(emitter);
        Runnable remove = () -> group.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(t -> remove.run());
        return emitter;
    }

    private void ping(SseEmitter em) {
        try {
            em.send(SseEmitter.event().comment("ping"));
        } catch (IOException | IllegalStateException ex) {
            drop(em);
        }
    }

    private void send(SseEmitter em, String name, Object data) {
        try {
            em.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException ex) {
            log.debug("Dropping subscriber: {}", ex.getMessage());
            drop(em);
        }
    }

    private void drop(SseEmitter em) {
        feed.remove(em);
        perMatch.values().forEach(s -> s.remove(em));
        try {
            em.complete();
        } catch (RuntimeException ignored) {
            // already closed
        }
    }
}
