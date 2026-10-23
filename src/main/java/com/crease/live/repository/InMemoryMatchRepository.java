package com.crease.live.repository;

import com.crease.live.domain.Match;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Lock-free map of matches; per-match consistency is handled by each match's own lock. */
@Repository
public class InMemoryMatchRepository implements MatchRepository {
    private final Map<Long, Match> matches = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong(1000);

    @Override
    public long nextId() {
        return ids.incrementAndGet();
    }

    @Override
    public Match save(Match match) {
        matches.put(match.getId(), match);
        return match;
    }

    @Override
    public Optional<Match> findById(long id) {
        return Optional.ofNullable(matches.get(id));
    }

    @Override
    public Collection<Match> findAll() {
        return Collections.unmodifiableCollection(matches.values());
    }

    @Override
    public void delete(long id) {
        matches.remove(id);
    }
}
