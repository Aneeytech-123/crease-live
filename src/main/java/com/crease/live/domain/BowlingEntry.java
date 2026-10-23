package com.crease.live.domain;

/** Mutable bowling figures for one bowler in one innings. Guarded by the owning match's lock. */
public class BowlingEntry {
    private final Player player;
    private int legalBalls;
    private int maidens;
    private int runs;
    private int wickets;
    private int wides;
    private int noBalls;
    private int dots;
    private int runsThisOver;

    public BowlingEntry(Player player) {
        this.player = player;
    }

    public void concede(int runsConceded) {
        runs += runsConceded;
        runsThisOver += runsConceded;
    }

    public void legalBall(boolean dot) {
        legalBalls++;
        if (dot) dots++;
    }

    public void wide() { wides++; }
    public void noBall() { noBalls++; }
    public void wicket() { wickets++; }

    /** Closes the bowler's current over; a complete over with nothing conceded is a maiden. */
    public void closeOver(boolean completeOver) {
        if (completeOver && runsThisOver == 0) maidens++;
        runsThisOver = 0;
    }

    public int completedOvers() { return legalBalls / 6; }

    public double economy() {
        return legalBalls == 0 ? 0 : runs * 6.0 / legalBalls;
    }

    public Player getPlayer() { return player; }
    public int getLegalBalls() { return legalBalls; }
    public int getMaidens() { return maidens; }
    public int getRuns() { return runs; }
    public int getWickets() { return wickets; }
    public int getWides() { return wides; }
    public int getNoBalls() { return noBalls; }
    public int getDots() { return dots; }
}
