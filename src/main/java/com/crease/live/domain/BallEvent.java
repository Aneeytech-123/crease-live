package com.crease.live.domain;

import com.crease.live.domain.Enums.ExtraType;
import com.crease.live.domain.Enums.WicketType;

import java.time.Instant;

/**
 * One delivery, as recorded in the match's ball-by-ball log. Immutable, so it can be shared
 * freely with readers and pushed to subscribers without copying.
 */
public record BallEvent(
        long seq,
        int innings,
        String over,            // "16.3"
        long batterId,
        String batterName,
        long bowlerId,
        String bowlerName,
        int batRuns,
        ExtraType extraType,
        int extras,
        int totalRuns,
        WicketType wicketType,
        String dismissedName,
        String dismissal,
        String label,           // compact chip text: "4", "W", "1wd", "nb4", "2lb"
        String commentary,
        boolean overComplete,
        int scoreAfter,
        int wicketsAfter,
        Instant at) {

    public boolean isWicket() {
        return wicketType != null;
    }

    public boolean isBoundary() {
        return batRuns == 4 || batRuns == 6;
    }
}
