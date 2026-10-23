package com.crease.live.service;

import com.crease.live.domain.BallEvent;
import com.crease.live.domain.Enums.MatchStatus;
import com.crease.live.domain.Match;
import com.crease.live.domain.Team;
import com.crease.live.realtime.MatchUpdatedEvent;
import com.crease.live.repository.MatchRepository;
import com.crease.live.repository.TeamRepository;
import com.crease.live.service.Exceptions.InvalidBallException;
import com.crease.live.service.Exceptions.MatchStateException;
import com.crease.live.service.Exceptions.NotFoundException;
import com.crease.live.simulation.SquadFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Application service: owns match lifecycle and is the single entry point for score changes,
 * whether they come from the simulator, a human scorer, or an external feed. Each change runs
 * under the match's own lock and is published as a {@link MatchUpdatedEvent} after it commits.
 */
@Service
public class MatchService {

    private static final Comparator<Match> DISPLAY_ORDER = Comparator
            .comparingInt((Match m) -> switch (m.getStatus()) {
                case LIVE, INNINGS_BREAK -> 0;
                case UPCOMING -> 1;
                default -> 2;
            })
            .thenComparing(m -> m.getStatus() == MatchStatus.UPCOMING ? m.getScheduledStart() : Instant.EPOCH)
            .thenComparing(Match::getUpdatedAt, Comparator.reverseOrder());

    private final MatchRepository matches;
    private final TeamRepository teams;
    private final ScoringService scoring;
    private final ApplicationEventPublisher events;
    private final Random random = new Random();

    public MatchService(MatchRepository matches, TeamRepository teams, ScoringService scoring,
                        ApplicationEventPublisher events) {
        this.matches = matches;
        this.teams = teams;
        this.scoring = scoring;
        this.events = events;
    }

    // ------------------------------------------------------------------ queries

    public List<Match> list(MatchStatus status) {
        return matches.findAll().stream()
                .filter(m -> status == null || m.getStatus() == status
                        || (status == MatchStatus.LIVE && m.getStatus() == MatchStatus.INNINGS_BREAK))
                .sorted(DISPLAY_ORDER)
                .toList();
    }

    public Collection<Match> all() {
        return matches.findAll();
    }

    public Match get(long id) {
        return matches.findById(id).orElseThrow(() -> new NotFoundException("Match " + id + " not found"));
    }

    /** Ball-by-ball log, newest first, optionally filtered by innings and paged by sequence number. */
    public List<BallEvent> commentary(long id, Integer innings, int limit, Long beforeSeq) {
        Match m = get(id);
        int cap = Math.max(1, Math.min(limit, 300));
        return m.withLock(() -> {
            List<BallEvent> out = new ArrayList<>(cap);
            List<BallEvent> balls = m.getBalls();
            for (int i = balls.size() - 1; i >= 0 && out.size() < cap; i--) {
                BallEvent b = balls.get(i);
                if (beforeSeq != null && b.seq() >= beforeSeq) continue;
                if (innings != null && b.innings() != innings) continue;
                out.add(b);
            }
            return out;
        });
    }

    // ------------------------------------------------------------------ commands

    public Match create(long teamAId, long teamBId, int overs, String venue, Instant start, boolean autoSimulate) {
        if (teamAId == teamBId) throw new IllegalArgumentException("A team cannot play itself");
        Team a = teams.findById(teamAId).orElseThrow(() -> new NotFoundException("Team " + teamAId + " not found"));
        Team b = teams.findById(teamBId).orElseThrow(() -> new NotFoundException("Team " + teamBId + " not found"));
        if (overs < 1 || overs > 50) throw new IllegalArgumentException("overs must be between 1 and 50");
        if (venue == null || venue.isBlank()) venue = SquadFactory.homeVenue(a);
        String title = a.shortName() + " vs " + b.shortName() + " · " + (overs == 20 ? "T20" : overs == 10 ? "T10" : overs == 50 ? "ODI" : overs + "-over");
        Match m = new Match(matches.nextId(), title, venue, overs, a, b, start);
        m.setAutoSimulate(autoSimulate);
        matches.save(m);
        publish(m, null);
        return m;
    }

    public Match start(long id) {
        Match m = get(id);
        mutate(m, x -> {
            scoring.startMatch(x, random);
            return null;
        });
        return m;
    }

    public Match setAutoSimulate(long id, boolean enabled) {
        Match m = get(id);
        mutate(m, x -> {
            x.setAutoSimulate(enabled);
            x.touch();
            return null;
        });
        return m;
    }

    /** Records a delivery from a human scorer or an external feed. */
    public BallEvent recordBall(long id, BallInput input) {
        Match m = get(id);
        if (m.getStatus() == MatchStatus.UPCOMING) throw new MatchStateException("Start the match before scoring");
        return mutate(m, x -> scoring.applyBall(x, input));
    }

    /**
     * Bowls one simulated delivery. Uses tryLock so that if a scorer or another worker is busy
     * with this match, the tick is skipped instead of piling up threads.
     */
    public Optional<BallEvent> simulateNextBall(long id, Function<Match, BallInput> feed) {
        Match m = matches.findById(id).orElse(null);
        if (m == null || !m.tryLock()) return Optional.empty();
        BallEvent ball;
        try {
            if (m.getStatus() == MatchStatus.INNINGS_BREAK
                    && m.getBreakUntil() != null && m.getBreakUntil().isAfter(Instant.now())) {
                return Optional.empty();
            }
            if (m.getStatus() != MatchStatus.LIVE && m.getStatus() != MatchStatus.INNINGS_BREAK) return Optional.empty();
            ball = scoring.applyBall(m, feed.apply(m));
        } catch (InvalidBallException | MatchStateException e) {
            return Optional.empty();
        } finally {
            m.unlock();
        }
        publish(m, ball);
        return Optional.of(ball);
    }

    /** Plays deliveries silently until {@code stop} is true (used to seed realistic mid-match states). */
    public void fastForward(Match m, Function<Match, BallInput> feed, Predicate<Match> stop) {
        m.withLock(() -> {
            if (m.getStatus() == MatchStatus.UPCOMING) scoring.startMatch(m, random);
            int guard = 0;
            while (!stop.test(m) && m.getStatus() != MatchStatus.COMPLETED && guard++ < 1_000) {
                scoring.applyBall(m, feed.apply(m));
            }
            return null;
        });
    }

    public void delete(long id) {
        get(id);
        matches.delete(id);
        events.publishEvent(MatchUpdatedEvent.removed(id));
    }

    // ------------------------------------------------------------------ internals

    private <T> T mutate(Match m, Function<Match, T> change) {
        T result = m.withLock(() -> change.apply(m));
        publish(m, result instanceof BallEvent b ? b : null);
        return result;
    }

    private void publish(Match m, BallEvent ball) {
        events.publishEvent(MatchUpdatedEvent.changed(m.getId(), m.getVersion(), ball));
    }
}
