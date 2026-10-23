package com.crease.live.service;

import com.crease.live.domain.BallEvent;
import com.crease.live.domain.BattingEntry;
import com.crease.live.domain.BowlingEntry;
import com.crease.live.domain.Enums.ExtraType;
import com.crease.live.domain.Enums.MatchStatus;
import com.crease.live.domain.Enums.WicketType;
import com.crease.live.domain.Innings;
import com.crease.live.domain.Match;
import com.crease.live.domain.Player;
import com.crease.live.domain.Team;
import com.crease.live.service.Exceptions.InvalidBallException;
import com.crease.live.service.Exceptions.MatchStateException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The cricket rules engine. Pure state transitions on a {@link Match}: callers must hold the
 * match lock (see {@link MatchService}). Handles extras, dismissals, strike rotation, over and
 * bowler changes, maidens, partnerships, innings transitions, targets and results.
 */
@Service
public class ScoringService {

    public static final int BALLS_PER_OVER = 6;
    static final Duration INNINGS_BREAK = Duration.ofSeconds(8);

    private final CommentaryGenerator commentary;

    public ScoringService(CommentaryGenerator commentary) {
        this.commentary = commentary;
    }

    // ------------------------------------------------------------------ match lifecycle

    public void startMatch(Match m, Random rnd) {
        if (m.getStatus() != MatchStatus.UPCOMING) {
            throw new MatchStateException("Match " + m.getId() + " has already started");
        }
        Team tossWinner = rnd.nextBoolean() ? m.getTeamA() : m.getTeamB();
        boolean electedToBat = rnd.nextBoolean();
        m.setToss(tossWinner, electedToBat);
        Team battingFirst = electedToBat ? tossWinner : m.opponentOf(tossWinner);
        m.addInnings(openInnings(m, 1, battingFirst, m.opponentOf(battingFirst), null));
        m.setStatus(MatchStatus.LIVE);
        m.setStartedAt(Instant.now());
        m.touch();
    }

    private Innings openInnings(Match m, int number, Team bat, Team bowl, Integer target) {
        Innings inn = new Innings(number, bat, bowl, target);
        inn.setStrikerId(inn.bringInNextBatter().orElseThrow().id());
        inn.setNonStrikerId(inn.bringInNextBatter().orElseThrow().id());
        inn.setBowlerId(pickNextBowler(inn, m.getOversPerInnings()).id());
        return inn;
    }

    // ------------------------------------------------------------------ deliveries

    public BallEvent applyBall(Match m, BallInput in) {
        if (m.getStatus() == MatchStatus.INNINGS_BREAK) {
            m.setStatus(MatchStatus.LIVE);         // a ball being bowled ends the break
            m.setBreakUntil(null);
        }
        if (m.getStatus() != MatchStatus.LIVE) {
            throw new MatchStateException("Match " + m.getId() + " is " + m.getStatus() + ", not LIVE");
        }
        Innings inn = m.currentInnings();
        validate(in);
        maybeOverrideBowler(inn, in);

        ExtraType ex = in.extraType();
        int ballsBefore = inn.getLegalBalls();
        String overLabel = (ballsBefore / BALLS_PER_OVER) + "." + (ballsBefore % BALLS_PER_OVER + 1);

        Player bowlerP = inn.getBowling().player(inn.getBowlerId()).orElseThrow();
        BowlingEntry bowler = inn.bowlerEntry(bowlerP);
        BattingEntry striker = inn.batter(inn.getStrikerId());
        Player strikerP = striker.getPlayer();

        // --- runs
        int batRuns = (ex == ExtraType.NONE || ex == ExtraType.NO_BALL) ? in.batRuns() : 0;
        int extras = switch (ex) {
            case NONE -> 0;
            case WIDE, NO_BALL -> 1 + in.extraRuns();
            case BYE, LEG_BYE -> in.extraRuns();
        };
        int total = batRuns + extras;
        int runsRun = switch (ex) {                // what decides strike rotation
            case NONE, NO_BALL -> batRuns + (ex == ExtraType.NO_BALL ? in.extraRuns() : 0);
            case WIDE, BYE, LEG_BYE -> in.extraRuns();
        };

        striker.addBall(batRuns, ex != ExtraType.WIDE);
        int conceded = batRuns + (ex.chargedToBowler() ? extras : 0);
        bowler.concede(conceded);
        if (ex == ExtraType.WIDE) bowler.wide();
        if (ex == ExtraType.NO_BALL) bowler.noBall();
        inn.addExtras(ex, extras);
        inn.addRuns(total);
        boolean legal = ex.isLegalDelivery();
        if (legal) {
            bowler.legalBall(conceded == 0);
            inn.addLegalBall();
        }

        // --- dismissal
        WicketType wt = in.wicketType();
        String dismissal = null;
        String fielderName = null;
        if (wt != null) {
            Player fielder = resolveFielder(inn, wt, in.fielderId(), bowlerP);
            fielderName = fielder == null ? null : fielder.name();
            dismissal = dismissalText(wt, bowlerP, fielder);
            striker.dismiss(dismissal);
            if (wt.creditedToBowler()) bowler.wicket();
            inn.recordWicket(inn.getRuns() + "-" + (inn.getWickets() + 1) + " (" + strikerP.name() + ", "
                    + oversText(inn.getLegalBalls()) + " ov)");
            // New batter takes the dismissed batter's end (on strike for catches under the current Laws).
            inn.setStrikerId(inn.bringInNextBatter().map(Player::id).orElse(null));
        } else if (runsRun % 2 == 1) {
            inn.swapStrike();
        }

        String label = label(batRuns, ex, extras, wt);
        inn.getCurrentOver().add(label);
        String text = in.commentary() != null && !in.commentary().isBlank()
                ? in.commentary()
                : commentary.describe(bowlerP.name(), strikerP.name(), batRuns, ex, in.extraRuns(), wt, fielderName);

        // --- end of over / innings
        boolean overComplete = legal && inn.getLegalBalls() % BALLS_PER_OVER == 0;
        int maxWickets = inn.getBatting().players().size() - 1;
        boolean allOut = inn.getWickets() >= maxWickets || inn.getStrikerId() == null;
        boolean oversDone = inn.getLegalBalls() >= m.getOversPerInnings() * BALLS_PER_OVER;
        boolean chased = inn.getTarget() != null && inn.getRuns() >= inn.getTarget();

        BallEvent event = new BallEvent(m.getBalls().size() + 1L, inn.getNumber(), overLabel,
                strikerP.id(), strikerP.name(), bowlerP.id(), bowlerP.name(), batRuns, ex, extras, total,
                wt, wt == null ? null : strikerP.name(), dismissal, label, text, overComplete,
                inn.getRuns(), inn.getWickets(), Instant.now());
        m.addBall(event);

        if (overComplete) {
            bowler.closeOver(true);
            inn.closeOver();
            inn.setLastOverBowlerId(bowlerP.id());
            if (inn.getStrikerId() != null) inn.swapStrike();
        }
        if (allOut || oversDone || chased) {
            if (!overComplete) {
                bowler.closeOver(false);
                if (inn.overInProgress()) inn.closeOver();
            }
            endInnings(m, inn);
        } else if (overComplete) {
            inn.setBowlerId(pickNextBowler(inn, m.getOversPerInnings()).id());
        }
        m.touch();
        return event;
    }

    private void endInnings(Match m, Innings inn) {
        inn.markComplete();
        inn.setBowlerId(null);
        if (inn.getNumber() == 1) {
            m.addInnings(openInnings(m, 2, inn.getBowling(), inn.getBatting(), inn.getRuns() + 1));
            m.setStatus(MatchStatus.INNINGS_BREAK);
            m.setBreakUntil(Instant.now().plus(INNINGS_BREAK));
            return;
        }
        Innings first = m.getInnings().get(0);
        int target = inn.getTarget();
        if (inn.getRuns() >= target) {
            int wktsLeft = inn.getBatting().players().size() - 1 - inn.getWickets();
            int ballsLeft = m.getOversPerInnings() * BALLS_PER_OVER - inn.getLegalBalls();
            m.complete(inn.getBatting(), inn.getBatting().name() + " won by " + plural(wktsLeft, "wicket")
                    + " (" + plural(ballsLeft, "ball") + " left)");
        } else if (inn.getRuns() == target - 1) {
            m.complete(null, "Match tied");
        } else {
            m.complete(first.getBatting(), first.getBatting().name() + " won by "
                    + plural(target - 1 - inn.getRuns(), "run"));
        }
    }

    // ------------------------------------------------------------------ helpers

    private void validate(BallInput in) {
        ExtraType ex = in.extraType();
        if (in.batRuns() < 0 || in.batRuns() > 7) throw new InvalidBallException("batRuns must be between 0 and 7");
        if (in.extraRuns() < 0 || in.extraRuns() > 6) throw new InvalidBallException("extraRuns must be between 0 and 6");
        if ((ex == ExtraType.WIDE || ex == ExtraType.BYE || ex == ExtraType.LEG_BYE) && in.batRuns() > 0) {
            throw new InvalidBallException("Runs cannot be scored off the bat on a " + ex);
        }
        if ((ex == ExtraType.BYE || ex == ExtraType.LEG_BYE) && in.extraRuns() == 0 && in.wicketType() == null) {
            throw new InvalidBallException(ex + " must have at least one run");
        }
        if (in.wicketType() != null && !in.wicketType().allowedOn(ex)) {
            throw new InvalidBallException(in.wicketType() + " is not possible on a " + ex);
        }
    }

    private void maybeOverrideBowler(Innings inn, BallInput in) {
        if (in.bowlerId() == null || in.bowlerId().equals(inn.getBowlerId())) return;
        if (inn.overInProgress()) throw new InvalidBallException("Bowler can only be changed at the start of an over");
        Player p = inn.getBowling().player(in.bowlerId())
                .orElseThrow(() -> new InvalidBallException("Player " + in.bowlerId() + " is not in the fielding side"));
        if (p.id() == (inn.getLastOverBowlerId() == null ? -1 : inn.getLastOverBowlerId())) {
            throw new InvalidBallException(p.name() + " bowled the previous over");
        }
        inn.setBowlerId(p.id());
    }

    /** Fewest overs first, respecting the per-bowler quota and the no-consecutive-overs Law. */
    Player pickNextBowler(Innings inn, int oversPerInnings) {
        int quota = Math.max(1, (int) Math.ceil(oversPerInnings / 5.0));
        List<Player> pool = inn.getBowling().players().stream().filter(p -> p.role().canBowl()).toList();
        if (pool.size() < 2) pool = inn.getBowling().players();
        long last = inn.getLastOverBowlerId() == null ? -1 : inn.getLastOverBowlerId();
        List<Player> eligible = pool.stream()
                .filter(p -> p.id() != last && oversBowled(inn, p) < quota).toList();
        if (eligible.isEmpty()) eligible = pool.stream().filter(p -> p.id() != last).toList();
        int fewest = eligible.stream().mapToInt(p -> oversBowled(inn, p)).min().orElse(0);
        List<Player> best = eligible.stream().filter(p -> oversBowled(inn, p) == fewest)
                .sorted(Comparator.comparing((Player p) -> p.role() == com.crease.live.domain.Enums.Role.BOWLER ? 0 : 1))
                .toList();
        // A little variety among the specialist options keeps the attack from rotating mechanically.
        long specialists = best.stream().filter(p -> p.role() == com.crease.live.domain.Enums.Role.BOWLER).count();
        int span = (int) Math.max(1, specialists);
        return best.get(ThreadLocalRandom.current().nextInt(span));
    }

    private static int oversBowled(Innings inn, Player p) {
        return inn.existingBowlerEntry(p.id()).map(BowlingEntry::completedOvers).orElse(0);
    }

    private static Player resolveFielder(Innings inn, WicketType wt, Long fielderId, Player bowler) {
        if (fielderId != null) {
            return inn.getBowling().player(fielderId)
                    .orElseThrow(() -> new InvalidBallException("Fielder " + fielderId + " is not in the fielding side"));
        }
        if (wt == WicketType.STUMPED) return inn.getBowling().wicketKeeper().orElse(null);
        return null;
    }

    static String dismissalText(WicketType wt, Player bowler, Player fielder) {
        String b = bowler.name();
        return switch (wt) {
            case BOWLED -> "b " + b;
            case LBW -> "lbw b " + b;
            case HIT_WICKET -> "hit wicket b " + b;
            case CAUGHT -> fielder == null ? "c sub b " + b
                    : fielder.id() == bowler.id() ? "c & b " + b
                    : "c " + fielder.name() + " b " + b;
            case STUMPED -> "st " + (fielder == null ? "keeper" : fielder.name()) + " b " + b;
            case RUN_OUT -> fielder == null ? "run out" : "run out (" + fielder.name() + ")";
        };
    }

    static String label(int batRuns, ExtraType ex, int extras, WicketType wt) {
        String base = switch (ex) {
            case NONE -> String.valueOf(batRuns);
            case WIDE -> (extras > 1 ? extras : "") + "wd";
            case NO_BALL -> "nb" + (batRuns > 0 ? batRuns : "");
            case BYE -> extras + "b";
            case LEG_BYE -> extras + "lb";
        };
        if (wt == null) return base;
        return (ex == ExtraType.NONE && batRuns == 0) ? "W" : base + "+W";
    }

    public static String oversText(int legalBalls) {
        int rem = legalBalls % BALLS_PER_OVER;
        return (legalBalls / BALLS_PER_OVER) + (rem == 0 ? "" : "." + rem);
    }

    private static String plural(int n, String word) {
        return n + " " + word + (n == 1 ? "" : "s");
    }
}
