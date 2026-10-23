package com.crease.live.realtime;

import com.crease.live.domain.BallEvent;

/** Published after every committed change to a match (ball bowled, match started, settings changed). */
public record MatchUpdatedEvent(long matchId, long version, BallEvent ball, boolean removed) {

    public static MatchUpdatedEvent changed(long matchId, long version, BallEvent ball) {
        return new MatchUpdatedEvent(matchId, version, ball, false);
    }

    public static MatchUpdatedEvent removed(long matchId) {
        return new MatchUpdatedEvent(matchId, -1, null, true);
    }
}
