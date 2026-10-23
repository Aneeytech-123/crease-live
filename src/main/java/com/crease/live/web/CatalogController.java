package com.crease.live.web;

import com.crease.live.realtime.LiveUpdateBroadcaster;
import com.crease.live.repository.TeamRepository;
import com.crease.live.service.Exceptions.NotFoundException;
import com.crease.live.service.MatchInsightsService;
import com.crease.live.view.Views.Overview;
import com.crease.live.view.Views.PlayerProfile;
import com.crease.live.view.Views.PlayerRef;
import com.crease.live.view.Views.PlayerStats;
import com.crease.live.view.Views.TeamView;
import com.crease.live.domain.Team;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/** Teams, players, leaderboards, platform overview and the global live feed. */
@RestController
@RequestMapping("/api")
public class CatalogController {

    private final TeamRepository teams;
    private final MatchInsightsService insights;
    private final LiveUpdateBroadcaster broadcaster;

    public CatalogController(TeamRepository teams, MatchInsightsService insights, LiveUpdateBroadcaster broadcaster) {
        this.teams = teams;
        this.insights = insights;
        this.broadcaster = broadcaster;
    }

    @GetMapping("/teams")
    public List<TeamView> teams() {
        return teams.findAll().stream().map(CatalogController::view).toList();
    }

    @GetMapping("/teams/{id}")
    public TeamView team(@PathVariable long id) {
        return teams.findById(id).map(CatalogController::view)
                .orElseThrow(() -> new NotFoundException("Team " + id + " not found"));
    }

    /** ?type=runs|wickets|sixes|strikerate|economy */
    @GetMapping("/players/leaderboard")
    public List<PlayerStats> leaderboard(@RequestParam(defaultValue = "runs") String type,
                                         @RequestParam(defaultValue = "10") int limit) {
        return insights.leaderboard(type, limit);
    }

    @GetMapping("/players/{id}")
    public PlayerProfile player(@PathVariable long id) {
        return insights.player(id);
    }

    @GetMapping("/overview")
    public Overview overview() {
        return insights.overview(broadcaster.subscriberCount());
    }

    /** Global real-time feed: a {@code card} event whenever any match changes. */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return broadcaster.subscribeFeed();
    }

    private static TeamView view(Team t) {
        return new TeamView(t.id(), t.name(), t.shortName(), t.color(),
                t.players().stream().map(p -> new PlayerRef(p.id(), p.name(), p.role().name())).toList());
    }
}
