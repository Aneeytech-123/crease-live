package com.crease.live.simulation;

import com.crease.live.config.SimulationProperties;
import com.crease.live.domain.Enums.MatchStatus;
import com.crease.live.domain.Match;
import com.crease.live.domain.Team;
import com.crease.live.repository.TeamRepository;
import com.crease.live.service.MatchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Loads squads and a realistic spread of fixtures on startup: a tight chase in progress,
 * a first innings in progress, a finished match with a full summary, and one about to start.
 */
@Component
public class DemoDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final TeamRepository teams;
    private final MatchService matches;
    private final BallOutcomeGenerator feed;
    private final SimulationProperties props;

    public DemoDataSeeder(TeamRepository teams, MatchService matches, BallOutcomeGenerator feed,
                          SimulationProperties props) {
        this.teams = teams;
        this.matches = matches;
        this.feed = feed;
        this.props = props;
    }

    @Override
    public void run(String... args) {
        List<Team> squads = SquadFactory.createAll(2026L);
        squads.forEach(teams::save);
        if (!props.seedDemoData()) return;

        Instant now = Instant.now();
        Team pun = squads.get(0), mum = squads.get(1), blr = squads.get(2),
                kol = squads.get(3), hyd = squads.get(4), jai = squads.get(5);

        // 1. Chase under way: first innings done, a few overs into the second.
        Match chase = matches.create(pun.id(), mum.id(), 20, SquadFactory.homeVenue(pun), now, true);
        matches.fastForward(chase, feed, m -> m.getInnings().size() == 2 && m.currentInnings().getLegalBalls() >= 6 * 9);

        // 2. First innings in progress.
        Match first = matches.create(blr.id(), kol.id(), 20, SquadFactory.homeVenue(blr), now, true);
        matches.fastForward(first, feed, m -> m.currentInnings().getLegalBalls() >= 6 * 7);

        // 3. A completed match, for summaries and player stats.
        Match done = matches.create(hyd.id(), jai.id(), 20, SquadFactory.homeVenue(hyd), now, false);
        matches.fastForward(done, feed, m -> m.getStatus() == MatchStatus.COMPLETED);

        // 4. A short T10 fixture that starts shortly after boot.
        matches.create(jai.id(), pun.id(), 10, SquadFactory.homeVenue(jai), now.plusSeconds(40), true);

        log.info("Seeded {} teams and {} matches", squads.size(), matches.all().size());
    }
}
