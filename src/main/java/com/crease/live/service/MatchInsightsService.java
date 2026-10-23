package com.crease.live.service;

import com.crease.live.domain.Enums.MatchStatus;
import com.crease.live.domain.Match;
import com.crease.live.domain.Player;
import com.crease.live.domain.Team;
import com.crease.live.repository.TeamRepository;
import com.crease.live.view.Views.BatterRow;
import com.crease.live.view.Views.BowlerRow;
import com.crease.live.view.Views.InningsLine;
import com.crease.live.view.Views.InningsView;
import com.crease.live.view.Views.MatchSummary;
import com.crease.live.view.Views.Overview;
import com.crease.live.view.Views.Performer;
import com.crease.live.view.Views.PlayerMatchLine;
import com.crease.live.view.Views.PlayerProfile;
import com.crease.live.view.Views.PlayerStats;
import com.crease.live.view.Views.Scorecard;
import com.crease.live.service.Exceptions.NotFoundException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Derived analytics: match summaries, player statistics across all matches, and leaderboards.
 * Everything is computed from cached immutable scorecards, so it never contends with scoring.
 * Aggregates are memoised against a fingerprint of all match versions.
 */
@Service
public class MatchInsightsService {

    private final MatchService matches;
    private final ScorecardAssembler assembler;
    private final TeamRepository teams;

    private volatile long cachedFingerprint = Long.MIN_VALUE;
    private volatile Map<Long, Acc> cachedStats = Map.of();

    public MatchInsightsService(MatchService matches, ScorecardAssembler assembler, TeamRepository teams) {
        this.matches = matches;
        this.assembler = assembler;
        this.teams = teams;
    }

    // ------------------------------------------------------------------ match summary

    public MatchSummary summary(long matchId) {
        Scorecard sc = assembler.snapshot(matches.get(matchId));
        List<InningsLine> lines = sc.toCard().innings();
        List<Performer> bats = new ArrayList<>();
        List<Performer> bowls = new ArrayList<>();
        int fours = 0, sixes = 0, extras = 0, wkts = 0;
        Map<Long, Double> impact = new HashMap<>();
        Map<Long, Performer> impactLabel = new HashMap<>();
        List<String> highlights = new ArrayList<>();

        for (InningsView inn : sc.innings()) {
            extras += inn.extras().total();
            wkts += inn.wickets();
            boolean winningSide = sc.winner() != null && sc.winner().id() == inn.battingTeam().id();
            for (BatterRow b : inn.battingCard()) {
                fours += b.fours();
                sixes += b.sixes();
                double score = b.runs() + (b.balls() > 10 ? (b.strikeRate() - 120) / 10 : 0) + (winningSide ? 8 : 0);
                impact.merge(b.playerId(), score, Double::sum);
                impactLabel.putIfAbsent(b.playerId(), performer(b.playerId(), b.name(), inn.battingTeam().shortName(),
                        b.runs() + (b.out() ? "" : "*") + " (" + b.balls() + ")", ""));
            }
            inn.battingCard().stream()
                    .sorted(Comparator.comparingInt(BatterRow::runs).reversed().thenComparingInt(BatterRow::balls))
                    .limit(3)
                    .forEach(b -> bats.add(performer(b.playerId(), b.name(), inn.battingTeam().shortName(),
                            b.runs() + (b.out() ? "" : "*") + " (" + b.balls() + ")",
                            b.fours() + "×4, " + b.sixes() + "×6, SR " + b.strikeRate())));
            boolean bowlingWon = sc.winner() != null && sc.winner().id() == inn.bowlingTeam().id();
            for (BowlerRow b : inn.bowlingCard()) {
                double score = b.wickets() * 22 + (b.legalBalls() >= 12 ? (8 - b.economy()) * 3 : 0) + (bowlingWon ? 8 : 0);
                impact.merge(b.playerId(), score, Double::sum);
                impactLabel.merge(b.playerId(), performer(b.playerId(), b.name(), inn.bowlingTeam().shortName(),
                        b.wickets() + "/" + b.runs(), ""), (x, y) -> new Performer(x.playerId(), x.name(), x.teamShort(),
                        x.figures() + " & " + y.figures(), ""));
            }
            inn.bowlingCard().stream()
                    .sorted(Comparator.comparingInt(BowlerRow::wickets).reversed().thenComparingInt(BowlerRow::runs))
                    .limit(3)
                    .forEach(b -> bowls.add(performer(b.playerId(), b.name(), inn.bowlingTeam().shortName(),
                            b.wickets() + "/" + b.runs() + " (" + b.overs() + ")", "Econ " + b.economy())));
            int bestOver = inn.overRuns().stream().mapToInt(Integer::intValue).max().orElse(0);
            if (bestOver >= 15) highlights.add(inn.battingTeam().shortName() + " took " + bestOver + " off a single over");
            if (inn.bestPartnership() >= 50)
                highlights.add(inn.battingTeam().shortName() + " built a " + inn.bestPartnership() + "-run stand");
        }
        if (sixes > 0) highlights.add(sixes + " sixes and " + fours + " fours struck in the match");

        Performer pom = null;
        if (sc.status() == MatchStatus.COMPLETED) {
            pom = impact.entrySet().stream().max(Map.Entry.comparingByValue())
                    .map(e -> impactLabel.get(e.getKey())).orElse(null);
        }
        return new MatchSummary(sc.id(), sc.title(), sc.venue(), sc.status(),
                sc.result() != null ? sc.result() : sc.statusText(), sc.toss(), lines, bats, bowls, pom,
                fours, sixes, extras, wkts, highlights);
    }

    // ------------------------------------------------------------------ player stats

    public List<PlayerStats> leaderboard(String type, int limit) {
        Map<Long, Acc> stats = aggregate();
        Comparator<PlayerStats> order = switch (type == null ? "runs" : type.toLowerCase()) {
            case "wickets" -> Comparator.comparingInt(PlayerStats::wickets).reversed()
                    .thenComparingInt(PlayerStats::runsConceded);
            case "sixes" -> Comparator.comparingInt(PlayerStats::sixes).reversed();
            case "strikerate" -> Comparator.comparingDouble(PlayerStats::strikeRate).reversed();
            case "economy" -> Comparator.comparingDouble((PlayerStats s) -> s.economy() == null ? 99 : s.economy());
            default -> Comparator.comparingInt(PlayerStats::runs).reversed().thenComparingInt(PlayerStats::balls);
        };
        String t = type == null ? "runs" : type.toLowerCase();
        return stats.values().stream().map(Acc::toStats)
                .filter(s -> switch (t) {
                    case "wickets" -> s.wickets() > 0;
                    case "strikerate" -> s.balls() >= 20;
                    case "economy" -> s.ballsBowled() >= 24;
                    case "sixes" -> s.sixes() > 0;
                    default -> s.runs() > 0;
                })
                .sorted(order).limit(Math.max(1, Math.min(limit, 50))).toList();
    }

    public PlayerProfile player(long playerId) {
        Player p = teams.findPlayer(playerId).orElseThrow(() -> new NotFoundException("Player " + playerId + " not found"));
        Acc acc = aggregate().get(playerId);
        PlayerStats stats = acc != null ? acc.toStats() : new Acc(p, teams.findById(p.teamId()).orElseThrow()).toStats();
        return new PlayerProfile(stats, acc == null ? List.of() : List.copyOf(acc.recent));
    }

    public Overview overview(int subscribers) {
        int live = 0, upcoming = 0, completed = 0;
        long balls = 0, runs = 0, sixes = 0;
        for (Match m : matches.all()) {
            switch (m.getStatus()) {
                case LIVE, INNINGS_BREAK -> live++;
                case UPCOMING -> upcoming++;
                default -> completed++;
            }
            Scorecard sc = assembler.snapshot(m);
            for (InningsView i : sc.innings()) {
                balls += i.legalBalls();
                runs += i.runs();
                sixes += i.battingCard().stream().mapToInt(BatterRow::sixes).sum();
            }
        }
        return new Overview(live, upcoming, completed, balls, subscribers, runs, sixes);
    }

    private Map<Long, Acc> aggregate() {
        long fp = 17;
        for (Match m : matches.all()) fp = fp * 31 + m.getId() * 7919 + m.getVersion();
        if (fp == cachedFingerprint) return cachedStats;

        Map<Long, Acc> acc = new HashMap<>();
        Function<Long, Acc> lookup = id -> acc.computeIfAbsent(id, k -> {
            Player p = teams.findPlayer(k).orElseThrow();
            return new Acc(p, teams.findById(p.teamId()).orElseThrow());
        });
        List<Match> ordered = matches.all().stream()
                .filter(m -> m.getStatus() != MatchStatus.UPCOMING)
                .sorted(Comparator.comparing(Match::getUpdatedAt).reversed()).toList();
        for (Match m : ordered) {
            Scorecard sc = assembler.snapshot(m);
            Map<Long, String[]> lines = new HashMap<>();
            for (Team t : List.of(m.getTeamA(), m.getTeamB())) {
                for (Player p : t.players()) lookup.apply(p.id()).matches++;
            }
            for (InningsView inn : sc.innings()) {
                for (BatterRow b : inn.battingCard()) {
                    if (b.balls() == 0 && !b.out() && !b.atCrease()) continue;
                    lookup.apply(b.playerId()).bat(b);
                    lines.computeIfAbsent(b.playerId(), k -> new String[2])[0] = b.runs() + (b.out() ? "" : "*") + " (" + b.balls() + ")";
                }
                for (BowlerRow b : inn.bowlingCard()) {
                    lookup.apply(b.playerId()).bowl(b);
                    lines.computeIfAbsent(b.playerId(), k -> new String[2])[1] = b.wickets() + "/" + b.runs() + " (" + b.overs() + ")";
                }
            }
            lines.forEach((pid, l) -> {
                Acc a = lookup.apply(pid);
                if (a.recent.size() < 8) a.recent.add(new PlayerMatchLine(m.getId(), m.getTitle(), l[0], l[1]));
            });
        }
        cachedStats = acc;
        cachedFingerprint = fp;
        return acc;
    }

    private static Performer performer(long id, String name, String team, String figures, String detail) {
        return new Performer(id, name, team, figures, detail);
    }

    /** Mutable accumulator, private to a single aggregation pass. */
    private static final class Acc {
        final Player p;
        final Team team;
        int matches, innings, runs, balls, notOuts, highest, fours, sixes, fifties;
        boolean highestNotOut;
        int ballsBowled, runsConceded, wickets, bestW = -1, bestR;
        final List<PlayerMatchLine> recent = new ArrayList<>();

        Acc(Player p, Team team) {
            this.p = p;
            this.team = team;
        }

        void bat(BatterRow b) {
            innings++;
            runs += b.runs();
            balls += b.balls();
            fours += b.fours();
            sixes += b.sixes();
            if (!b.out()) notOuts++;
            if (b.runs() >= 50) fifties++;
            if (b.runs() > highest || (b.runs() == highest && !b.out())) {
                highest = b.runs();
                highestNotOut = !b.out();
            }
        }

        void bowl(BowlerRow b) {
            ballsBowled += b.legalBalls();
            runsConceded += b.runs();
            wickets += b.wickets();
            if (b.wickets() > bestW || (b.wickets() == bestW && b.runs() < bestR)) {
                bestW = b.wickets();
                bestR = b.runs();
            }
        }

        PlayerStats toStats() {
            int outs = innings - notOuts;
            return new PlayerStats(p.id(), p.name(), p.role().name(), team.shortName(), team.color(), matches, innings,
                    runs, balls, notOuts, highest, highestNotOut, fours, sixes, fifties,
                    outs == 0 ? null : r2(runs / (double) outs), balls == 0 ? 0 : r2(runs * 100.0 / balls),
                    ballsBowled, runsConceded, wickets, bestW < 0 ? null : bestW + "/" + bestR,
                    ballsBowled == 0 ? null : r2(runsConceded * 6.0 / ballsBowled));
        }

        private static double r2(double v) {
            return Math.round(v * 100.0) / 100.0;
        }
    }
}
