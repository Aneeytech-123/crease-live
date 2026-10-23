package com.crease.live.domain;

/** Mutable batting figures for one batter in one innings. Guarded by the owning match's lock. */
public class BattingEntry {
    private final Player player;
    private final int position;
    private int runs;
    private int balls;
    private int fours;
    private int sixes;
    private boolean out;
    private String dismissal;

    public BattingEntry(Player player, int position) {
        this.player = player;
        this.position = position;
    }

    public void addBall(int runsScored, boolean countsAsFaced) {
        runs += runsScored;
        if (countsAsFaced) balls++;
        if (runsScored == 4) fours++;
        if (runsScored == 6) sixes++;
    }

    public void dismiss(String description) {
        this.out = true;
        this.dismissal = description;
    }

    public double strikeRate() {
        return balls == 0 ? 0 : runs * 100.0 / balls;
    }

    public Player getPlayer() { return player; }
    public int getPosition() { return position; }
    public int getRuns() { return runs; }
    public int getBalls() { return balls; }
    public int getFours() { return fours; }
    public int getSixes() { return sixes; }
    public boolean isOut() { return out; }
    public String getDismissal() { return dismissal; }
}
