package com.crease.live.view;

import com.crease.live.domain.BallEvent;
import com.crease.live.domain.Enums.MatchStatus;
import com.crease.live.domain.Team;

import java.time.Instant;
import java.util.List;

/**
 * Immutable read models returned by the API and pushed over the live stream. They are built
 * once per match version and shared by every reader (see {@code ScorecardAssembler}).
 */
public final class Views {
    private Views() {
    }

    public record TeamRef(long id, String name, String shortName, String color) {
        public static TeamRef of(Team t) {
            return t == null ? null : new TeamRef(t.id(), t.name(), t.shortName(), t.color());
        }
    }

    public record PlayerRef(long id, String name, String role) {
    }

    public record TeamView(long id, String name, String shortName, String color, List<PlayerRef> players) {
    }

    public record InningsLine(int number, String teamShort, int runs, int wickets, String overs, boolean complete) {
    }

    /** Compact view used for match lists and the global live feed. */
    public record MatchCard(long id, String title, String venue, int oversPerInnings, MatchStatus status,
                            TeamRef teamA, TeamRef teamB, List<InningsLine> innings, String statusText,
                            Instant scheduledStart, boolean autoSimulate, long version) {
    }

    public record BatterRow(long playerId, String name, String role, int runs, int balls, int fours, int sixes,
                            double strikeRate, String status, boolean out, boolean onStrike, boolean atCrease) {
    }

    public record BowlerRow(long playerId, String name, String overs, int legalBalls, int maidens, int runs,
                            int wickets, double economy, int wides, int noBalls, int dots, boolean bowling) {
    }

    public record ExtrasView(int total, int byes, int legByes, int wides, int noBalls) {
    }

    public record InningsView(int number, TeamRef battingTeam, TeamRef bowlingTeam, int runs, int wickets,
                              String overs, int legalBalls, Integer target, boolean complete, double runRate,
                              ExtrasView extras, List<BatterRow> battingCard, List<BowlerRow> bowlingCard,
                              List<String> yetToBat, List<String> fallOfWickets, List<Integer> overRuns,
                              List<Integer> overWickets, int bestPartnership) {
    }

    public record LiveView(BatterRow striker, BatterRow nonStriker, BowlerRow bowler, double currentRunRate,
                           Double requiredRunRate, Integer target, Integer runsNeeded, Integer ballsLeft,
                           int partnershipRuns, int partnershipBalls, List<String> thisOver, Integer projectedScore) {
    }

    /** Full scorecard: the main read model, pushed to match subscribers on every ball. */
    public record Scorecard(long id, String title, String venue, int oversPerInnings, MatchStatus status,
                            String statusText, TeamRef teamA, TeamRef teamB, String toss,
                            List<InningsView> innings, LiveView live, BallEvent lastBall, String result,
                            TeamRef winner, boolean autoSimulate, long version, Instant updatedAt,
                            Instant scheduledStart, Instant startedAt) {

        public MatchCard toCard() {
            List<InningsLine> lines = innings.stream()
                    .map(i -> new InningsLine(i.number(), i.battingTeam().shortName(), i.runs(), i.wickets(),
                            i.overs(), i.complete()))
                    .toList();
            return new MatchCard(id, title, venue, oversPerInnings, status, teamA, teamB, lines, statusText,
                    scheduledStart, autoSimulate, version);
        }
    }

    public record Performer(long playerId, String name, String teamShort, String figures, String detail) {
    }

    public record MatchSummary(long id, String title, String venue, MatchStatus status, String result,
                               String toss, List<InningsLine> innings, List<Performer> topBatters,
                               List<Performer> topBowlers, Performer playerOfTheMatch, int totalFours,
                               int totalSixes, int totalExtras, int totalWickets, List<String> highlights) {
    }

    public record PlayerStats(long playerId, String name, String role, String teamShort, String teamColor,
                              int matches, int innings, int runs, int balls, int notOuts, int highest,
                              boolean highestNotOut, int fours, int sixes, int fifties, Double average,
                              double strikeRate, int ballsBowled, int runsConceded, int wickets,
                              String bestBowling, Double economy) {
    }

    public record PlayerMatchLine(long matchId, String title, String batting, String bowling) {
    }

    public record PlayerProfile(PlayerStats stats, List<PlayerMatchLine> recent) {
    }

    public record Overview(int live, int upcoming, int completed, long ballsBowled, int subscribers,
                           long totalRuns, long totalSixes) {
    }

    public record BallResult(BallEvent ball, Scorecard scorecard) {
    }
}
