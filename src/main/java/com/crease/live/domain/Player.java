package com.crease.live.domain;

import com.crease.live.domain.Enums.Role;

/** Immutable player identity. Per-match figures live in {@link BattingEntry} / {@link BowlingEntry}. */
public record Player(long id, String name, Role role, long teamId) {
}
