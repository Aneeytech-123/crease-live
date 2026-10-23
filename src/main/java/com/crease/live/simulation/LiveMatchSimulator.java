package com.crease.live.simulation;

import com.crease.live.config.SimulationProperties;
import com.crease.live.domain.Enums.MatchStatus;
import com.crease.live.domain.Match;
import com.crease.live.domain.Team;
import com.crease.live.repository.TeamRepository;
import com.crease.live.service.MatchService;
import com.crease.live.service.ScorecardAssembler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Drives the "live feed". On every tick it starts fixtures whose start time has arrived and
 * dispatches one delivery per live match to a worker pool, so hundreds of matches progress in
 * parallel. It also keeps a rolling schedule of fixtures and prunes old completed matches.
 */
@Component
public class LiveMatchSimulator {

    private static final Logger log = LoggerFactory.getLogger(LiveMatchSimulator.class);
    private static final int MAX_COMPLETED_KEPT = 12;

    private final MatchService matches;
    private final TeamRepository teams;
    private final ScorecardAssembler assembler;
    private final BallOutcomeGenerator feed;
    private final SimulationProperties props;
    private final Executor workers;

    public LiveMatchSimulator(MatchService matches, TeamRepository teams, ScorecardAssembler assembler,
                              BallOutcomeGenerator feed, SimulationProperties props,
                              @Qualifier("simulationExecutor") Executor workers) {
        this.matches = matches;
        this.teams = teams;
        this.assembler = assembler;
        this.feed = feed;
        this.props = props;
        this.workers = workers;
    }

    @Scheduled(fixedDelayString = "${cricket.simulation.tick-millis:2500}", initialDelay = 3000)
    public void tick() {
        if (!props.enabled()) return;
        Instant now = Instant.now();
        for (Match m : matches.all()) {
            if (!m.isAutoSimulate()) continue;
            long id = m.getId();
            switch (m.getStatus()) {
                case UPCOMING -> {
                    if (!m.getScheduledStart().isAfter(now)) workers.execute(() -> startQuietly(id));
                }
                case LIVE, INNINGS_BREAK -> {
                    if (ThreadLocalRandom.current().nextDouble() < props.ballProbability()) {
                        workers.execute(() -> matches.simulateNextBall(id, feed));
                    }
                }
                default -> { }
            }
        }
        if (props.autoReplenish()) {
            replenish(now);
            prune();
        }
    }

    private void startQuietly(long id) {
        try {
            matches.start(id);
            log.info("Match {} started", id);
        } catch (RuntimeException e) {
            log.debug("Could not start match {}: {}", id, e.getMessage());
        }
    }

    /** Keeps at least {@code minActiveMatches} fixtures live or upcoming. */
    private void replenish(Instant now) {
        long active = matches.all().stream().filter(m -> m.getStatus() != MatchStatus.COMPLETED
                && m.getStatus() != MatchStatus.ABANDONED).count();
        if (active >= props.minActiveMatches()) return;
        List<Team> pool = new ArrayList<>(teams.findAll());
        if (pool.size() < 2) return;
        // Pick the two teams that are not currently busy, preferring those who played least recently.
        List<Team> free = pool.stream().filter(t -> matches.all().stream().noneMatch(m -> m.involves(t.id())
                && m.getStatus() != MatchStatus.COMPLETED)).toList();
        List<Team> candidates = free.size() >= 2 ? new ArrayList<>(free) : pool;
        java.util.Collections.shuffle(candidates);
        Team home = candidates.get(0);
        Team away = candidates.get(1);
        Match m = matches.create(home.id(), away.id(), props.defaultOvers(), SquadFactory.homeVenue(home),
                now.plusSeconds(props.startDelaySeconds()), true);
        log.info("Scheduled {} (match {})", m.getTitle(), m.getId());
    }

    private void prune() {
        List<Match> done = matches.all().stream().filter(m -> m.getStatus() == MatchStatus.COMPLETED)
                .sorted(Comparator.comparing(Match::getUpdatedAt).reversed()).toList();
        for (int i = MAX_COMPLETED_KEPT; i < done.size(); i++) {
            long id = done.get(i).getId();
            matches.delete(id);
            assembler.evict(id);
        }
    }
}
