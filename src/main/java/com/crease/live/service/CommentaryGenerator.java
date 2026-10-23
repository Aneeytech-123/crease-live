package com.crease.live.service;

import com.crease.live.domain.Enums.ExtraType;
import com.crease.live.domain.Enums.WicketType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Turns a delivery outcome into a line of ball-by-ball commentary. */
@Component
public class CommentaryGenerator {

    private static final List<String> DOT = List.of(
            "defended solidly back to the bowler.",
            "beaten outside off! Lovely shape on that one.",
            "pushed to cover, no run.",
            "left alone outside off stump.",
            "full and straight, jammed out to mid-on.",
            "short of a length, tucked to short leg.");
    private static final List<String> ONE = List.of(
            "worked off the pads to deep square for one.",
            "dabbed to third man, they jog through.",
            "pushed into the gap at cover, quick single.",
            "clipped to midwicket and they scamper across.",
            "driven down to long-on for a single.");
    private static final List<String> TWO = List.of(
            "placed into the gap at deep midwicket, they come back for two.",
            "punched off the back foot, good running gets them two.",
            "slapped to sweeper cover, comfortable couple.");
    private static final List<String> THREE = List.of(
            "chased down just inside the rope, they run three.",
            "driven into the vast gap at long-off, three taken.");
    private static final List<String> FOUR = List.of(
            "FOUR! Creamed through the covers, no need to run.",
            "FOUR! Short and pulled hard in front of square.",
            "FOUR! Edged... and it flies past the slip cordon.",
            "FOUR! Glorious straight drive, past the bowler.",
            "FOUR! Late cut, beats third man to the rope.");
    private static final List<String> SIX = List.of(
            "SIX! Launched over long-on and into the stands!",
            "SIX! Picked up off the pads, sails over fine leg.",
            "SIX! Down the track and lofted straight over the sightscreen.",
            "SIX! Slog-swept into the second tier!");
    private static final List<String> CAUGHT = List.of(
            "OUT! Top-edged, and %s settles under it.",
            "OUT! Driven uppishly, straight to %s.",
            "OUT! Thin edge, taken by %s.");
    private static final List<String> BOWLED = List.of(
            "OUT! Bowled him! Through the gate and the off stump is cartwheeling.",
            "OUT! Clean bowled, the yorker crashes into the base of middle.");
    private static final List<String> LBW = List.of(
            "OUT! Trapped in front, the finger goes up.",
            "OUT! Big appeal for LBW... given! That was hitting leg.");

    public String describe(String bowler, String batter, int batRuns, ExtraType extra, int extraRuns,
                           WicketType wicket, String fielder) {
        String prefix = bowler + " to " + batter + ", ";
        if (wicket != null) {
            return prefix + switch (wicket) {
                case CAUGHT -> String.format(pick(CAUGHT), fielder == null ? "the fielder" : fielder);
                case BOWLED -> pick(BOWLED);
                case LBW -> pick(LBW);
                case RUN_OUT -> "OUT! Run out! Direct hit from " + (fielder == null ? "the deep" : fielder) + ", well short.";
                case STUMPED -> "OUT! Stumped! Down the track, misses, and " + (fielder == null ? "the keeper" : fielder) + " whips the bails off.";
                case HIT_WICKET -> "OUT! Hit wicket! Treads on the stumps going back.";
            };
        }
        return prefix + switch (extra) {
            case WIDE -> extraRuns >= 4 ? "wide, and it races away for five wides!" : "wide, sprayed down the leg side.";
            case NO_BALL -> "NO BALL, overstepped! Free hit coming" + (batRuns > 0 ? ", and " + batRuns + " off the bat." : ".");
            case BYE -> extraRuns + " bye" + (extraRuns > 1 ? "s" : "") + ", beats bat and keeper.";
            case LEG_BYE -> extraRuns + " leg bye" + (extraRuns > 1 ? "s" : "") + ", off the pad.";
            case NONE -> switch (batRuns) {
                case 0 -> pick(DOT);
                case 1 -> pick(ONE);
                case 2 -> pick(TWO);
                case 3 -> pick(THREE);
                case 4 -> pick(FOUR);
                case 6 -> pick(SIX);
                default -> batRuns + " runs.";
            };
        };
    }

    private static String pick(List<String> options) {
        return options.get(ThreadLocalRandom.current().nextInt(options.size()));
    }
}
