package com.crease.live.simulation;

import com.crease.live.domain.Enums.ExtraType;
import com.crease.live.domain.Enums.WicketType;
import com.crease.live.domain.Innings;
import com.crease.live.domain.Match;
import com.crease.live.domain.Player;
import com.crease.live.service.BallInput;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

/**
 * Simulated data feed. Produces realistic deliveries from a weighted outcome table that shifts
 * with the phase of the innings (powerplay, middle, death), wickets in hand and chase pressure.
 * Must be invoked while the match lock is held, since it reads live innings state.
 */
@Component
public class BallOutcomeGenerator implements Function<Match, BallInput> {

    enum Outcome { DOT, ONE, TWO, THREE, FOUR, SIX, WICKET, WIDE, NO_BALL, LEG_BYE, BYE }

    private static final Map<Outcome, Double> BASE = new EnumMap<>(Map.ofEntries(
            Map.entry(Outcome.DOT, 34.0), Map.entry(Outcome.ONE, 36.0), Map.entry(Outcome.TWO, 7.0),
            Map.entry(Outcome.THREE, 0.8), Map.entry(Outcome.FOUR, 11.0), Map.entry(Outcome.SIX, 4.8),
            Map.entry(Outcome.WICKET, 5.6), Map.entry(Outcome.WIDE, 3.2), Map.entry(Outcome.NO_BALL, 0.6),
            Map.entry(Outcome.LEG_BYE, 1.6), Map.entry(Outcome.BYE, 0.5)));

    private static final Map<WicketType, Double> DISMISSALS = new EnumMap<>(Map.of(
            WicketType.CAUGHT, 55.0, WicketType.BOWLED, 18.0, WicketType.LBW, 15.0,
            WicketType.RUN_OUT, 7.0, WicketType.STUMPED, 4.0, WicketType.HIT_WICKET, 1.0));

    @Override
    public BallInput apply(Match m) {
        return next(m, ThreadLocalRandom.current());
    }

    public BallInput next(Match m, Random rnd) {
        Innings inn = m.currentInnings();
        Map<Outcome, Double> w = new EnumMap<>(BASE);
        int overs = m.getOversPerInnings();
        int over = inn.getLegalBalls() / 6;
        int powerplayEnd = (int) Math.ceil(overs * 0.3);
        int deathStart = overs - (int) Math.ceil(overs * 0.2);

        if (over < powerplayEnd) {
            scale(w, Outcome.FOUR, 1.3); scale(w, Outcome.SIX, 0.8); scale(w, Outcome.DOT, 1.05);
        } else if (over >= deathStart) {
            scale(w, Outcome.SIX, 1.8); scale(w, Outcome.FOUR, 1.25); scale(w, Outcome.WICKET, 1.4);
            scale(w, Outcome.DOT, 0.85); scale(w, Outcome.TWO, 1.3);
        } else {
            scale(w, Outcome.ONE, 1.15); scale(w, Outcome.FOUR, 0.85); scale(w, Outcome.SIX, 0.9);
            scale(w, Outcome.WICKET, 0.9);
        }
        if (inn.getWickets() >= 7) {                         // the tail
            scale(w, Outcome.SIX, 0.6); scale(w, Outcome.FOUR, 0.8); scale(w, Outcome.WICKET, 1.3);
        }
        if (inn.getTarget() != null) {                       // chase pressure
            int ballsLeft = overs * 6 - inn.getLegalBalls();
            double rrr = ballsLeft == 0 ? 0 : (inn.getTarget() - inn.getRuns()) * 6.0 / ballsLeft;
            if (rrr > 11) {
                scale(w, Outcome.SIX, 1.6); scale(w, Outcome.FOUR, 1.25); scale(w, Outcome.WICKET, 1.5); scale(w, Outcome.DOT, 0.9);
            } else if (rrr < 6) {
                scale(w, Outcome.SIX, 0.7); scale(w, Outcome.WICKET, 0.75); scale(w, Outcome.ONE, 1.15);
            }
        }

        return switch (sample(w, rnd)) {
            case DOT -> BallInput.runs(0);
            case ONE -> BallInput.runs(1);
            case TWO -> BallInput.runs(2);
            case THREE -> BallInput.runs(3);
            case FOUR -> BallInput.runs(4);
            case SIX -> BallInput.runs(6);
            case WIDE -> BallInput.extra(ExtraType.WIDE, rnd.nextDouble() < 0.05 ? 4 : 0);
            case NO_BALL -> new BallInput(pickNoBallRuns(rnd), ExtraType.NO_BALL, 0, null, null, null, null);
            case LEG_BYE -> BallInput.extra(ExtraType.LEG_BYE, rnd.nextDouble() < 0.15 ? 4 : 1);
            case BYE -> BallInput.extra(ExtraType.BYE, rnd.nextDouble() < 0.3 ? 4 : 1);
            case WICKET -> wicket(inn, rnd);
        };
    }

    private BallInput wicket(Innings inn, Random rnd) {
        WicketType type = sample(DISMISSALS, rnd);
        List<Player> fielders = inn.getBowling().players();
        Long fielder = switch (type) {
            case CAUGHT -> {
                double r = rnd.nextDouble();
                if (r < 0.12) yield inn.getBowlerId();                                      // caught & bowled
                if (r < 0.32) yield inn.getBowling().wicketKeeper().map(Player::id).orElse(null);
                yield fielders.get(rnd.nextInt(fielders.size())).id();
            }
            case RUN_OUT -> fielders.get(rnd.nextInt(fielders.size())).id();
            case STUMPED -> inn.getBowling().wicketKeeper().map(Player::id).orElse(null);
            default -> null;
        };
        return BallInput.wicket(type, fielder);
    }

    private static int pickNoBallRuns(Random rnd) {
        double r = rnd.nextDouble();
        return r < 0.5 ? 0 : r < 0.8 ? 1 : r < 0.92 ? 4 : 6;
    }

    private static void scale(Map<Outcome, Double> w, Outcome o, double factor) {
        w.computeIfPresent(o, (k, v) -> v * factor);
    }

    private static <T> T sample(Map<T, Double> weights, Random rnd) {
        double total = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        double r = rnd.nextDouble() * total;
        T last = null;
        for (Map.Entry<T, Double> e : weights.entrySet()) {
            last = e.getKey();
            r -= e.getValue();
            if (r <= 0) return last;
        }
        return last;
    }
}
