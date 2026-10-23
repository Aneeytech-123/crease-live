package com.crease.live.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Mutable state of one innings. All mutation happens inside {@code ScoringService} while the
 * owning {@link Match}'s lock is held; readers take immutable snapshots under the same lock.
 */
public class Innings {
    private final int number;
    private final Team batting;
    private final Team bowling;
    private final Integer target;            // null for the first innings

    private int runs;
    private int wickets;
    private int legalBalls;
    private int byes, legByes, wides, noBalls;

    private final Map<Long, BattingEntry> battingCard = new LinkedHashMap<>();
    private final Map<Long, BowlingEntry> bowlingCard = new LinkedHashMap<>();
    private int nextBatterIndex;

    private Long strikerId;
    private Long nonStrikerId;
    private Long bowlerId;
    private Long lastOverBowlerId;

    private final List<String> currentOver = new ArrayList<>();
    private final List<Integer> overRuns = new ArrayList<>();      // runs per completed over (charts)
    private final List<Integer> overWickets = new ArrayList<>();
    private int runsThisOver;
    private int wicketsThisOver;

    private final List<String> fallOfWickets = new ArrayList<>();
    private int partnershipRuns;
    private int partnershipBalls;
    private int bestPartnership;
    private boolean complete;

    public Innings(int number, Team batting, Team bowling, Integer target) {
        this.number = number;
        this.batting = batting;
        this.bowling = bowling;
        this.target = target;
    }

    // ---- batting order -----------------------------------------------------------------

    /** Sends in the next batter in the order; returns empty when nobody is left. */
    public Optional<Player> bringInNextBatter() {
        if (nextBatterIndex >= batting.players().size()) return Optional.empty();
        Player p = batting.players().get(nextBatterIndex++);
        battingCard.put(p.id(), new BattingEntry(p, nextBatterIndex));
        return Optional.of(p);
    }

    public BattingEntry batter(long playerId) {
        BattingEntry e = battingCard.get(playerId);
        if (e == null) throw new IllegalStateException("Player " + playerId + " is not batting");
        return e;
    }

    public BowlingEntry bowlerEntry(Player p) {
        return bowlingCard.computeIfAbsent(p.id(), id -> new BowlingEntry(p));
    }

    public Optional<BowlingEntry> existingBowlerEntry(long playerId) {
        return Optional.ofNullable(bowlingCard.get(playerId));
    }

    public void swapStrike() {
        Long t = strikerId;
        strikerId = nonStrikerId;
        nonStrikerId = t;
    }

    // ---- scoring primitives -------------------------------------------------------------

    public void addRuns(int total) {
        runs += total;
        runsThisOver += total;
        partnershipRuns += total;
        bestPartnership = Math.max(bestPartnership, partnershipRuns);
    }

    public void addLegalBall() {
        legalBalls++;
        partnershipBalls++;
    }

    public void addExtras(Enums.ExtraType type, int amount) {
        switch (type) {
            case WIDE -> wides += amount;
            case NO_BALL -> noBalls += amount;
            case BYE -> byes += amount;
            case LEG_BYE -> legByes += amount;
            case NONE -> { }
        }
    }

    public void recordWicket(String fallText) {
        wickets++;
        wicketsThisOver++;
        fallOfWickets.add(fallText);
        partnershipRuns = 0;
        partnershipBalls = 0;
    }

    /** Closes the current over (complete or, at innings end, partial) for the charts. */
    public void closeOver() {
        overRuns.add(runsThisOver);
        overWickets.add(wicketsThisOver);
        runsThisOver = 0;
        wicketsThisOver = 0;
        currentOver.clear();
    }

    public boolean overInProgress() {
        return !currentOver.isEmpty();
    }

    public int totalExtras() {
        return byes + legByes + wides + noBalls;
    }

    // ---- accessors ----------------------------------------------------------------------

    public int getNumber() { return number; }
    public Team getBatting() { return batting; }
    public Team getBowling() { return bowling; }
    public Integer getTarget() { return target; }
    public int getRuns() { return runs; }
    public int getWickets() { return wickets; }
    public int getLegalBalls() { return legalBalls; }
    public int getByes() { return byes; }
    public int getLegByes() { return legByes; }
    public int getWides() { return wides; }
    public int getNoBalls() { return noBalls; }
    public Collection<BattingEntry> getBattingCard() { return Collections.unmodifiableCollection(battingCard.values()); }
    public Collection<BowlingEntry> getBowlingCard() { return Collections.unmodifiableCollection(bowlingCard.values()); }
    public int getNextBatterIndex() { return nextBatterIndex; }
    public Long getStrikerId() { return strikerId; }
    public void setStrikerId(Long id) { this.strikerId = id; }
    public Long getNonStrikerId() { return nonStrikerId; }
    public void setNonStrikerId(Long id) { this.nonStrikerId = id; }
    public Long getBowlerId() { return bowlerId; }
    public void setBowlerId(Long id) { this.bowlerId = id; }
    public Long getLastOverBowlerId() { return lastOverBowlerId; }
    public void setLastOverBowlerId(Long id) { this.lastOverBowlerId = id; }
    public List<String> getCurrentOver() { return currentOver; }
    public List<Integer> getOverRuns() { return Collections.unmodifiableList(overRuns); }
    public List<Integer> getOverWickets() { return Collections.unmodifiableList(overWickets); }
    public int getRunsThisOver() { return runsThisOver; }
    public List<String> getFallOfWickets() { return Collections.unmodifiableList(fallOfWickets); }
    public int getPartnershipRuns() { return partnershipRuns; }
    public int getPartnershipBalls() { return partnershipBalls; }
    public int getBestPartnership() { return bestPartnership; }
    public boolean isComplete() { return complete; }
    public void markComplete() { this.complete = true; }
}
