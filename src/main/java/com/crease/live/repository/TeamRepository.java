package com.crease.live.repository;

import com.crease.live.domain.Player;
import com.crease.live.domain.Team;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory squad registry with a player index for O(1) player lookups. */
@Repository
public class TeamRepository {
    private final Map<Long, Team> teams = new ConcurrentHashMap<>();
    private final Map<Long, Player> players = new ConcurrentHashMap<>();

    public Team save(Team team) {
        teams.put(team.id(), team);
        team.players().forEach(p -> players.put(p.id(), p));
        return team;
    }

    public Optional<Team> findById(long id) {
        return Optional.ofNullable(teams.get(id));
    }

    public List<Team> findAll() {
        return teams.values().stream().sorted(Comparator.comparing(Team::name)).toList();
    }

    public Optional<Player> findPlayer(long playerId) {
        return Optional.ofNullable(players.get(playerId));
    }
}
