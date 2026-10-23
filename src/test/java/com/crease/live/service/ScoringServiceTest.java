package com.crease.live.service;

import com.crease.live.domain.BattingEntry;
import com.crease.live.domain.BowlingEntry;
import com.crease.live.domain.Enums.ExtraType;
import com.crease.live.domain.Enums.MatchStatus;
import com.crease.live.domain.Enums.WicketType;
import com.crease.live.domain.Innings;
import com.crease.live.domain.Match;
import com.crease.live.domain.Team;
import com.crease.live.simulation.BallOutcomeGenerator;
import com.crease.live.simulation.SquadFactory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScoringServiceTest {

    private final ScoringService scoring = new ScoringService(new CommentaryGenerator());
    private final List<Team> teams = SquadFactory.createAll(7L);

    private Match newMatch(int overs) {
        Match m = new Match(1, "test", "venue", overs, teams.get(0), teams.get(1), Instant.now());
        scoring.startMatch(m, new Random(3));
        return m;
    }

    @Test
    void sixDotBallsCompleteAMaidenOverAndRotateStrikeAndBowler() {
        Match m = newMatch(20);
        Innings inn = m.currentInnings();
        Long striker = inn.getStrikerId(), nonStriker = inn.getNonStrikerId(), bowler = inn.getBowlerId();

        for (int i = 0; i < 6; i++) scoring.applyBall(m, BallInput.runs(0));

        assertEquals(6, inn.getLegalBalls());
        assertEquals(List.of(0), inn.getOverRuns());
        assertEquals(nonStriker, inn.getStrikerId(), "strike changes ends at the end of an over");
        assertEquals(striker, inn.getNonStrikerId());
        assertNotEquals(bowler, inn.getBowlerId(), "no bowler may bowl consecutive overs");
        BowlingEntry b = inn.existingBowlerEntry(bowler).orElseThrow();
        assertEquals(1, b.getMaidens());
        assertTrue(m.getBalls().get(5).overComplete());
    }

    @Test
    void wideIsNotALegalDeliveryAndIsChargedToTheBowler() {
        Match m = newMatch(20);
        Innings inn = m.currentInnings();
        long striker = inn.getStrikerId();

        var ev = scoring.applyBall(m, BallInput.extra(ExtraType.WIDE, 0));

        assertEquals(0, inn.getLegalBalls());
        assertEquals(1, inn.getRuns());
        assertEquals(1, inn.getWides());
        assertEquals(0, inn.batter(striker).getBalls(), "a wide is not a ball faced");
        assertEquals(1, inn.existingBowlerEntry(inn.getBowlerId()).orElseThrow().getRuns());
        assertEquals("wd", ev.label());
        assertEquals("0.1", ev.over());
    }

    @Test
    void oddRunsRotateStrikeButBoundariesDoNot() {
        Match m = newMatch(20);
        Innings inn = m.currentInnings();
        Long a = inn.getStrikerId(), b = inn.getNonStrikerId();

        scoring.applyBall(m, BallInput.runs(1));
        assertEquals(b, inn.getStrikerId());
        scoring.applyBall(m, BallInput.runs(4));
        assertEquals(b, inn.getStrikerId());
        scoring.applyBall(m, BallInput.runs(3));
        assertEquals(a, inn.getStrikerId());

        BattingEntry bb = inn.batter(b);
        assertEquals(7, bb.getRuns());
        assertEquals(1, bb.getFours());
        assertEquals(8, inn.getRuns());
    }

    @Test
    void byesCountForTheTeamButNotAgainstTheBowlerOrBatter() {
        Match m = newMatch(20);
        Innings inn = m.currentInnings();
        long striker = inn.getStrikerId();

        scoring.applyBall(m, BallInput.extra(ExtraType.BYE, 4));

        assertEquals(4, inn.getRuns());
        assertEquals(4, inn.getByes());
        assertEquals(0, inn.batter(striker).getRuns());
        assertEquals(1, inn.batter(striker).getBalls());
        assertEquals(0, inn.existingBowlerEntry(inn.getBowlerId()).orElseThrow().getRuns());
    }

    @Test
    void noBallAddsPenaltyAndBatRunsAndIsReBowled() {
        Match m = newMatch(20);
        Innings inn = m.currentInnings();
        long striker = inn.getStrikerId();

        var ev = scoring.applyBall(m, new BallInput(4, ExtraType.NO_BALL, 0, null, null, null, null));

        assertEquals(5, inn.getRuns());
        assertEquals(0, inn.getLegalBalls());
        assertEquals(4, inn.batter(striker).getRuns());
        assertEquals(1, inn.batter(striker).getBalls(), "a no-ball counts as a ball faced");
        assertEquals(5, inn.existingBowlerEntry(inn.getBowlerId()).orElseThrow().getRuns());
        assertEquals("nb4", ev.label());
    }

    @Test
    void wicketBringsInNextBatterOnStrikeAndCreditsBowler() {
        Match m = newMatch(20);
        Innings inn = m.currentInnings();
        long out = inn.getStrikerId();
        long bowler = inn.getBowlerId();
        long fielder = inn.getBowling().players().get(2).id();

        var ev = scoring.applyBall(m, BallInput.wicket(WicketType.CAUGHT, fielder));

        assertTrue(inn.batter(out).isOut());
        assertTrue(inn.batter(out).getDismissal().startsWith("c "));
        assertEquals(Long.valueOf(inn.getBatting().players().get(2).id()), inn.getStrikerId());
        assertEquals(1, inn.getWickets());
        assertEquals(1, inn.existingBowlerEntry(bowler).orElseThrow().getWickets());
        assertEquals(1, inn.getFallOfWickets().size());
        assertEquals("W", ev.label());
    }

    @Test
    void runOutIsNotCreditedToTheBowler() {
        Match m = newMatch(20);
        Innings inn = m.currentInnings();
        long bowler = inn.getBowlerId();

        scoring.applyBall(m, BallInput.wicket(WicketType.RUN_OUT, null));

        assertEquals(1, inn.getWickets());
        assertEquals(0, inn.existingBowlerEntry(bowler).orElseThrow().getWickets());
    }

    @Test
    void impossibleDeliveriesAreRejected() {
        Match m = newMatch(20);
        assertThrows(Exceptions.InvalidBallException.class,
                () -> scoring.applyBall(m, new BallInput(2, ExtraType.WIDE, 0, null, null, null, null)));
        assertThrows(Exceptions.InvalidBallException.class,
                () -> scoring.applyBall(m, new BallInput(0, ExtraType.NO_BALL, 0, WicketType.LBW, null, null, null)));
        assertThrows(Exceptions.InvalidBallException.class,
                () -> scoring.applyBall(m, BallInput.extra(ExtraType.LEG_BYE, 0)));
        assertEquals(0, m.currentInnings().getRuns(), "rejected deliveries leave the score untouched");
    }

    @Test
    void allOutEndsTheInningsAndSetsTarget() {
        Match m = newMatch(20);
        scoring.applyBall(m, BallInput.runs(6));
        for (int i = 0; i < 10; i++) scoring.applyBall(m, BallInput.wicket(WicketType.BOWLED, null));

        Innings first = m.getInnings().get(0);
        assertTrue(first.isComplete());
        assertEquals(10, first.getWickets());
        assertEquals(2, m.getInnings().size());
        assertEquals(Integer.valueOf(7), m.currentInnings().getTarget());
        assertEquals(MatchStatus.INNINGS_BREAK, m.getStatus());
    }

    @Test
    void successfulChaseIsWonByWickets() {
        Match m = newMatch(2);
        for (int i = 0; i < 12; i++) scoring.applyBall(m, BallInput.runs(1));   // 12 off 2 overs
        assertEquals(Integer.valueOf(13), m.currentInnings().getTarget());

        for (int i = 0; i < 2; i++) scoring.applyBall(m, BallInput.runs(6));
        scoring.applyBall(m, BallInput.runs(1));

        assertEquals(MatchStatus.COMPLETED, m.getStatus());
        assertEquals(m.getInnings().get(1).getBatting(), m.getWinner());
        assertTrue(m.getResult().contains("won by 10 wickets"), m.getResult());
        assertTrue(m.getResult().contains("9 balls left"), m.getResult());
    }

    @Test
    void failedChaseIsWonByRuns() {
        Match m = newMatch(1);
        for (int i = 0; i < 6; i++) scoring.applyBall(m, BallInput.runs(4));    // 24
        for (int i = 0; i < 6; i++) scoring.applyBall(m, BallInput.runs(1));    // 6

        assertEquals(MatchStatus.COMPLETED, m.getStatus());
        assertEquals(m.getInnings().get(0).getBatting(), m.getWinner());
        assertTrue(m.getResult().endsWith("won by 18 runs"), m.getResult());
    }

    @Test
    void levelScoresAreATie() {
        Match m = newMatch(1);
        for (int i = 0; i < 6; i++) scoring.applyBall(m, BallInput.runs(1));
        for (int i = 0; i < 6; i++) scoring.applyBall(m, BallInput.runs(1));
        assertEquals("Match tied", m.getResult());
        assertNull(m.getWinner());
    }

    @Test
    void simulatedMatchesAlwaysBalanceTheBooks() {
        BallOutcomeGenerator feed = new BallOutcomeGenerator();
        for (int seed = 0; seed < 40; seed++) {
            Random rnd = new Random(seed);
            Match m = new Match(seed, "sim", "venue", 20, teams.get(seed % 6), teams.get((seed + 1) % 6), Instant.now());
            scoring.startMatch(m, rnd);
            int guard = 0;
            while (m.getStatus() != MatchStatus.COMPLETED && guard++ < 1000) scoring.applyBall(m, feed.next(m, rnd));

            assertEquals(MatchStatus.COMPLETED, m.getStatus(), "match " + seed + " finished");
            assertNotNull(m.getResult());
            for (Innings inn : m.getInnings()) {
                int batRuns = inn.getBattingCard().stream().mapToInt(BattingEntry::getRuns).sum();
                assertEquals(inn.getRuns(), batRuns + inn.totalExtras(), "runs reconcile");
                int bowlerBalls = inn.getBowlingCard().stream().mapToInt(BowlingEntry::getLegalBalls).sum();
                assertEquals(inn.getLegalBalls(), bowlerBalls, "balls reconcile");
                assertTrue(inn.getLegalBalls() <= 120);
                assertTrue(inn.getWickets() <= 10);
                int overRuns = inn.getOverRuns().stream().mapToInt(Integer::intValue).sum();
                assertEquals(inn.getRuns(), overRuns, "per-over chart data reconciles");
                for (BowlingEntry b : inn.getBowlingCard()) {
                    assertTrue(b.completedOvers() <= 4, b.getPlayer().name() + " within quota");
                }
            }
            assertFalse(m.getBalls().isEmpty());
        }
    }
}
