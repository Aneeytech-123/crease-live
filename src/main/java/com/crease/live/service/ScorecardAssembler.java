package com.crease.live.service;

import com.crease.live.domain.BallEvent;
import com.crease.live.domain.BattingEntry;
import com.crease.live.domain.BowlingEntry;
import com.crease.live.domain.Enums.MatchStatus;
import com.crease.live.domain.Innings;
import com.crease.live.domain.Match;
import com.crease.live.domain.Player;
import com.crease.live.view.Views.BatterRow;
import com.crease.live.view.Views.BowlerRow;
import com.crease.live.view.Views.ExtrasView;
import com.crease.live.view.Views.InningsView;
import com.crease.live.view.Views.LiveView;
import com.crease.live.view.Views.Scorecard;
import com.crease.live.view.Views.TeamRef;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import static com.crease.live.service.ScoringService.BALLS_PER_OVER;
import static com.crease.live.service.ScoringService.oversText;

/**
 * Builds immutable {@link Scorecard} snapshots and caches one per match version. However many
 * clients poll or subscribe, a snapshot is assembled at most once per delivery.
 */
@Component
public class ScorecardAssembler {

    private final Map<Long, Scorecard> cache = new ConcurrentHashMap<>();

    public Scorecard snapshot(Match m) {
        Scorecard cached = cache.get(m.getId());
        if (cached != null && cached.version() == m.getVersion()) return cached;
        Scorecard fresh = m.withLock(() -> build(m));
        cache.merge(m.getId(), fresh, (old, neu) -> neu.version() >= old.version() ? neu : old);
        return fresh;
    }

    public void evict(long matchId) {
        cache.remove(matchId);
    }

    private Scorecard build(Match m) {
        List<InningsView> innings = m.getInnings().stream().map(i -> inningsView(m, i)).toList();
        BallEvent last = m.getBalls().isEmpty() ? null : m.getBalls().get(m.getBalls().size() - 1);
        LiveView live = (m.getStatus() == MatchStatus.LIVE || m.getStatus() == MatchStatus.INNINGS_BREAK)
                ? liveView(m, m.currentInnings()) : null;
        String toss = m.getTossWinner() == null ? null
                : m.getTossWinner().name() + " won the toss and chose to " + (m.isTossElectedToBat() ? "bat" : "bowl");
        return new Scorecard(m.getId(), m.getTitle(), m.getVenue(), m.getOversPerInnings(), m.getStatus(),
                statusText(m, toss), TeamRef.of(m.getTeamA()), TeamRef.of(m.getTeamB()), toss, innings, live, last,
                m.getResult(), TeamRef.of(m.getWinner()), m.isAutoSimulate(), m.getVersion(), m.getUpdatedAt(),
                m.getScheduledStart(), m.getStartedAt());
    }

    private InningsView inningsView(Match m, Innings i) {
        boolean live = !i.isComplete() && m.getStatus() != MatchStatus.COMPLETED;
        List<BatterRow> batting = i.getBattingCard().stream()
                .map(b -> batterRow(b, live && Objects.equals(b.getPlayer().id(), i.getStrikerId()),
                        live && !b.isOut()))
                .toList();
        List<BowlerRow> bowling = i.getBowlingCard().stream()
                .map(b -> bowlerRow(b, live && Objects.equals(b.getPlayer().id(), i.getBowlerId())))
                .toList();
        List<String> yetToBat = i.getBatting().players().stream()
                .skip(i.getNextBatterIndex()).map(Player::name).toList();
        ExtrasView extras = new ExtrasView(i.totalExtras(), i.getByes(), i.getLegByes(), i.getWides(), i.getNoBalls());
        List<Integer> overRuns = new java.util.ArrayList<>(i.getOverRuns());
        List<Integer> overWkts = new java.util.ArrayList<>(i.getOverWickets());
        if (i.overInProgress()) {                      // include the over in progress for live charts
            overRuns.add(i.getRunsThisOver());
            overWkts.add(0);
        }
        return new InningsView(i.getNumber(), TeamRef.of(i.getBatting()), TeamRef.of(i.getBowling()), i.getRuns(),
                i.getWickets(), oversText(i.getLegalBalls()), i.getLegalBalls(), i.getTarget(), i.isComplete(),
                rate(i.getRuns(), i.getLegalBalls()), extras, batting, bowling, yetToBat, i.getFallOfWickets(),
                List.copyOf(overRuns), List.copyOf(overWkts), i.getBestPartnership());
    }

    private LiveView liveView(Match m, Innings i) {
        BatterRow striker = i.getStrikerId() == null ? null : batterRow(i.batter(i.getStrikerId()), true, true);
        BatterRow non = i.getNonStrikerId() == null ? null : batterRow(i.batter(i.getNonStrikerId()), false, true);
        BowlerRow bowler = i.getBowlerId() == null ? null
                : i.existingBowlerEntry(i.getBowlerId()).map(b -> bowlerRow(b, true))
                .orElseGet(() -> i.getBowling().player(i.getBowlerId())
                        .map(p -> new BowlerRow(p.id(), p.name(), "0", 0, 0, 0, 0, 0, 0, 0, 0, true)).orElse(null));
        int totalBalls = m.getOversPerInnings() * BALLS_PER_OVER;
        int ballsLeft = totalBalls - i.getLegalBalls();
        Integer target = i.getTarget();
        Integer need = target == null ? null : Math.max(0, target - i.getRuns());
        Double rrr = target == null ? null : (ballsLeft == 0 ? null : round2(need * 6.0 / ballsLeft));
        Integer projected = target == null && i.getLegalBalls() >= BALLS_PER_OVER
                ? (int) Math.round(i.getRuns() + rate(i.getRuns(), i.getLegalBalls()) * ballsLeft / 6.0) : null;
        return new LiveView(striker, non, bowler, rate(i.getRuns(), i.getLegalBalls()), rrr, target, need,
                target == null ? null : ballsLeft, i.getPartnershipRuns(), i.getPartnershipBalls(),
                List.copyOf(i.getCurrentOver()), projected);
    }

    private static String statusText(Match m, String toss) {
        return switch (m.getStatus()) {
            case UPCOMING -> "Match yet to begin";
            case COMPLETED, ABANDONED -> m.getResult();
            case INNINGS_BREAK -> {
                Innings i = m.currentInnings();
                yield "Innings break · " + i.getBatting().name() + " need " + i.getTarget() + " to win";
            }
            case LIVE -> {
                Innings i = m.currentInnings();
                if (i.getTarget() == null) yield toss;
                int need = i.getTarget() - i.getRuns();
                int balls = m.getOversPerInnings() * BALLS_PER_OVER - i.getLegalBalls();
                yield i.getBatting().name() + " need " + need + " run" + (need == 1 ? "" : "s") + " from "
                        + balls + " ball" + (balls == 1 ? "" : "s");
            }
        };
    }

    private static BatterRow batterRow(BattingEntry b, boolean onStrike, boolean atCrease) {
        return new BatterRow(b.getPlayer().id(), b.getPlayer().name(), b.getPlayer().role().name(), b.getRuns(),
                b.getBalls(), b.getFours(), b.getSixes(), round2(b.strikeRate()),
                b.isOut() ? b.getDismissal() : "not out", b.isOut(), onStrike, atCrease);
    }

    private static BowlerRow bowlerRow(BowlingEntry b, boolean bowling) {
        return new BowlerRow(b.getPlayer().id(), b.getPlayer().name(), oversText(b.getLegalBalls()),
                b.getLegalBalls(), b.getMaidens(), b.getRuns(), b.getWickets(), round2(b.economy()), b.getWides(),
                b.getNoBalls(), b.getDots(), bowling);
    }

    private static double rate(int runs, int balls) {
        return balls == 0 ? 0 : round2(runs * 6.0 / balls);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
