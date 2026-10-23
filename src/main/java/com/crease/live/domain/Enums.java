package com.crease.live.domain;

/** Holder for the small enums used across the domain (kept in one file for readability). */
public final class Enums {
    private Enums() {
    }

    public enum Role {
        BATTER, WICKET_KEEPER, ALL_ROUNDER, BOWLER;

        public boolean canBowl() {
            return this == BOWLER || this == ALL_ROUNDER;
        }
    }

    public enum MatchStatus {
        UPCOMING, LIVE, INNINGS_BREAK, COMPLETED, ABANDONED
    }

    public enum ExtraType {
        NONE, WIDE, NO_BALL, BYE, LEG_BYE;

        /** Wides and no-balls do not count towards the six legal deliveries of an over. */
        public boolean isLegalDelivery() {
            return this != WIDE && this != NO_BALL;
        }

        /** Runs from wides and no-balls are charged to the bowler; byes and leg-byes are not. */
        public boolean chargedToBowler() {
            return this == WIDE || this == NO_BALL;
        }
    }

    public enum WicketType {
        BOWLED, CAUGHT, LBW, RUN_OUT, STUMPED, HIT_WICKET;

        public boolean creditedToBowler() {
            return this != RUN_OUT;
        }

        /** Which dismissals the Laws allow on a given kind of delivery. */
        public boolean allowedOn(ExtraType extra) {
            return switch (extra) {
                case NONE -> true;
                case WIDE -> this == STUMPED || this == RUN_OUT || this == HIT_WICKET;
                case NO_BALL, BYE, LEG_BYE -> this == RUN_OUT;
            };
        }
    }
}
