package com.crease.live.domain;

import java.util.List;
import java.util.Optional;

/** A squad of eleven players listed in batting order. */
public record Team(long id, String name, String shortName, String color, List<Player> players) {

    public Team {
        players = List.copyOf(players);
    }

    public Optional<Player> player(long playerId) {
        return players.stream().filter(p -> p.id() == playerId).findFirst();
    }

    public Optional<Player> wicketKeeper() {
        return players.stream().filter(p -> p.role() == Enums.Role.WICKET_KEEPER).findFirst();
    }
}
