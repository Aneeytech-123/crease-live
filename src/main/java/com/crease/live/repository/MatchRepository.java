package com.crease.live.repository;

import com.crease.live.domain.Match;

import java.util.Collection;
import java.util.Optional;

/**
 * Storage port for matches. The default implementation is in-memory; a Redis- or
 * database-backed implementation can be dropped in without touching services or controllers.
 */
public interface MatchRepository {
    long nextId();

    Match save(Match match);

    Optional<Match> findById(long id);

    Collection<Match> findAll();

    void delete(long id);
}
