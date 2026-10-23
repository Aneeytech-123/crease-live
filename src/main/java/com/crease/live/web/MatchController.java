package com.crease.live.web;

import com.crease.live.domain.BallEvent;
import com.crease.live.domain.Enums.MatchStatus;
import com.crease.live.domain.Match;
import com.crease.live.realtime.LiveUpdateBroadcaster;
import com.crease.live.service.MatchInsightsService;
import com.crease.live.service.MatchService;
import com.crease.live.service.ScorecardAssembler;
import com.crease.live.view.Views.BallResult;
import com.crease.live.view.Views.MatchCard;
import com.crease.live.view.Views.MatchSummary;
import com.crease.live.view.Views.Scorecard;
import com.crease.live.web.Requests.BallRequest;
import com.crease.live.web.Requests.CreateMatchRequest;
import com.crease.live.web.Requests.SimulationToggle;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/matches")
public class MatchController {

    private final MatchService matches;
    private final ScorecardAssembler assembler;
    private final MatchInsightsService insights;
    private final LiveUpdateBroadcaster broadcaster;

    public MatchController(MatchService matches, ScorecardAssembler assembler, MatchInsightsService insights,
                           LiveUpdateBroadcaster broadcaster) {
        this.matches = matches;
        this.assembler = assembler;
        this.insights = insights;
        this.broadcaster = broadcaster;
    }

    /** All matches (live first), optionally filtered: ?status=LIVE|UPCOMING|COMPLETED */
    @GetMapping
    public List<MatchCard> list(@RequestParam(required = false) MatchStatus status) {
        return matches.list(status).stream().map(m -> assembler.snapshot(m).toCard()).toList();
    }

    /** Full scorecard with live state (batters at the crease, bowler, required rate, this over). */
    @GetMapping("/{id}")
    public ResponseEntity<Scorecard> scorecard(@PathVariable long id) {
        Scorecard sc = assembler.snapshot(matches.get(id));
        return ResponseEntity.ok().eTag("\"" + sc.version() + "\"").body(sc);
    }

    /** Ball-by-ball commentary, newest first. Page backwards with ?before={seq}. */
    @GetMapping("/{id}/commentary")
    public List<BallEvent> commentary(@PathVariable long id,
                                      @RequestParam(required = false) Integer innings,
                                      @RequestParam(defaultValue = "30") int limit,
                                      @RequestParam(required = false) Long before) {
        return matches.commentary(id, innings, limit, before);
    }

    @GetMapping("/{id}/summary")
    public MatchSummary summary(@PathVariable long id) {
        return insights.summary(id);
    }

    /** Real-time stream for one match: events {@code scorecard} and {@code ball}. */
    @GetMapping(path = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable long id) {
        return broadcaster.subscribeMatch(matches.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MatchCard create(@Valid @RequestBody CreateMatchRequest req) {
        int overs = req.overs() == null ? 20 : req.overs();
        int delay = req.startsInSeconds() == null ? 30 : req.startsInSeconds();
        Match m = matches.create(req.teamAId(), req.teamBId(), overs, req.venue(), Instant.now().plusSeconds(delay),
                req.autoSimulate() == null || req.autoSimulate());
        return assembler.snapshot(m).toCard();
    }

    @PostMapping("/{id}/start")
    public Scorecard start(@PathVariable long id) {
        return assembler.snapshot(matches.start(id));
    }

    /** Pause or resume the automatic feed for a match (e.g. while a human scorer takes over). */
    @PatchMapping("/{id}/simulation")
    public MatchCard simulation(@PathVariable long id, @Valid @RequestBody SimulationToggle toggle) {
        return assembler.snapshot(matches.setAutoSimulate(id, toggle.enabled())).toCard();
    }

    /** Record a delivery. Validated against the Laws (e.g. no runs off the bat on a wide). */
    @PostMapping("/{id}/balls")
    @ResponseStatus(HttpStatus.CREATED)
    public BallResult recordBall(@PathVariable long id, @Valid @RequestBody BallRequest req) {
        BallEvent ball = matches.recordBall(id, req.toInput());
        return new BallResult(ball, assembler.snapshot(matches.get(id)));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        matches.delete(id);
        assembler.evict(id);
    }
}
