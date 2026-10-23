package com.crease.live.service;

import com.crease.live.domain.Enums.ExtraType;
import com.crease.live.domain.Enums.WicketType;

/**
 * What happened on one delivery, independent of where it came from (simulator, scorer UI,
 * or an external data feed).
 *
 * @param batRuns    runs off the bat (0 for wides, byes and leg-byes)
 * @param extraType  kind of extra, NONE for a normal delivery
 * @param extraRuns  runs run on a wide/bye/leg-bye (or byes off a no-ball); the 1-run wide/no-ball penalty is added automatically
 * @param wicketType dismissal, or null
 * @param fielderId  catcher / run-out fielder / keeper, optional
 * @param bowlerId   optional explicit bowler, honoured only at the start of an over
 * @param commentary optional custom commentary (otherwise generated)
 */
public record BallInput(int batRuns, ExtraType extraType, int extraRuns, WicketType wicketType,
                        Long fielderId, Long bowlerId, String commentary) {

    public BallInput {
        if (extraType == null) extraType = ExtraType.NONE;
    }

    public static BallInput runs(int r) {
        return new BallInput(r, ExtraType.NONE, 0, null, null, null, null);
    }

    public static BallInput wicket(WicketType type, Long fielderId) {
        return new BallInput(0, ExtraType.NONE, 0, type, fielderId, null, null);
    }

    public static BallInput extra(ExtraType type, int extraRuns) {
        return new BallInput(0, type, extraRuns, null, null, null, null);
    }
}
