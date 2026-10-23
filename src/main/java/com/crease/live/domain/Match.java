package com.crease.live.domain;

import com.crease.live.domain.Enums.MatchStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Aggregate root for a fixture. Every mutation or consistent read goes through
 * {@link #withLock(Supplier)}, so many matches can be scored concurrently without global locking.
 * {@link #getVersion()} increases on every change and drives snapshot caching and client ordering.
 */
public class Match {
    private final long id;
    private final String title;
    private final String venue;
    private final int oversPerInnings;
    private final Team teamA;
    private final Team teamB;

    private volatile MatchStatus status = MatchStatus.UPCOMING;
    private volatile Instant scheduledStart;
    private Instant startedAt;
    private Instant completedAt;
    private Team tossWinner;
    private boolean tossElectedToBat;

    private final List<Innings> innings = new ArrayList<>();
    private final List<BallEvent> balls = new ArrayList<>();
    private Team winner;
    private String result;

    private volatile Instant breakUntil;
    private volatile boolean autoSimulate = true;
    private volatile long version;
    private volatile Instant updatedAt = Instant.now();
    private final ReentrantLock lock = new ReentrantLock();

    public Match(long id, String title, String venue, int oversPerInnings, Team teamA, Team teamB, Instant scheduledStart) {
        this.id = id;
        this.title = title;
        this.venue = venue;
        this.oversPerInnings = oversPerInnings;
        this.teamA = teamA;
        this.teamB = teamB;
        this.scheduledStart = scheduledStart;
    }

    public <T> T withLock(Supplier<T> action) {
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    /** Non-blocking variant used by the simulator so a busy match is simply skipped this tick. */
    public boolean tryLock() {
        return lock.tryLock();
    }

    public void unlock() {
        lock.unlock();
    }

    public void touch() {
        version++;
        updatedAt = Instant.now();
    }

    public Innings currentInnings() {
        if (innings.isEmpty()) throw new IllegalStateException("Match has not started");
        return innings.get(innings.size() - 1);
    }

    public Team opponentOf(Team t) {
        return t.id() == teamA.id() ? teamB : teamA;
    }

    public boolean involves(long teamId) {
        return teamA.id() == teamId || teamB.id() == teamId;
    }

    public void addInnings(Innings i) { innings.add(i); }
    public void addBall(BallEvent b) { balls.add(b); }

    public void setToss(Team winner, boolean electedToBat) {
        this.tossWinner = winner;
        this.tossElectedToBat = electedToBat;
    }

    public void complete(Team winner, String result) {
        this.winner = winner;
        this.result = result;
        this.status = MatchStatus.COMPLETED;
        this.completedAt = Instant.now();
    }

    public long getId() { return id; }
    public String getTitle() { return title; }
    public String getVenue() { return venue; }
    public int getOversPerInnings() { return oversPerInnings; }
    public Team getTeamA() { return teamA; }
    public Team getTeamB() { return teamB; }
    public MatchStatus getStatus() { return status; }
    public void setStatus(MatchStatus status) { this.status = status; }
    public Instant getScheduledStart() { return scheduledStart; }
    public void setScheduledStart(Instant t) { this.scheduledStart = t; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant t) { this.startedAt = t; }
    public Instant getCompletedAt() { return completedAt; }
    public Team getTossWinner() { return tossWinner; }
    public boolean isTossElectedToBat() { return tossElectedToBat; }
    public List<Innings> getInnings() { return Collections.unmodifiableList(innings); }
    public List<BallEvent> getBalls() { return Collections.unmodifiableList(balls); }
    public Team getWinner() { return winner; }
    public String getResult() { return result; }
    public Instant getBreakUntil() { return breakUntil; }
    public void setBreakUntil(Instant t) { this.breakUntil = t; }
    public boolean isAutoSimulate() { return autoSimulate; }
    public void setAutoSimulate(boolean v) { this.autoSimulate = v; }
    public long getVersion() { return version; }
    public Instant getUpdatedAt() { return updatedAt; }
}
