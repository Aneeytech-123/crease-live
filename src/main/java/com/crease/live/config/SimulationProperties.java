package com.crease.live.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for the live-match simulation feed.
 *
 * @param enabled            whether the scheduler bowls balls automatically
 * @param tickMillis         delay between simulation ticks
 * @param ballProbability    chance a live match bowls a ball on a given tick (de-syncs matches)
 * @param autoReplenish      schedule new fixtures automatically so the platform never goes quiet
 * @param minActiveMatches   minimum LIVE + UPCOMING matches kept when replenishing
 * @param defaultOvers       overs per innings for auto-generated fixtures
 * @param startDelaySeconds  how far in the future auto-generated fixtures start
 * @param seedDemoData       create demo teams and matches on startup
 */
@ConfigurationProperties(prefix = "cricket.simulation")
public record SimulationProperties(
        boolean enabled,
        long tickMillis,
        double ballProbability,
        boolean autoReplenish,
        int minActiveMatches,
        int defaultOvers,
        int startDelaySeconds,
        boolean seedDemoData) {

    public SimulationProperties {
        if (tickMillis <= 0) tickMillis = 2500;
        if (ballProbability <= 0 || ballProbability > 1) ballProbability = 0.85;
        if (minActiveMatches <= 0) minActiveMatches = 3;
        if (defaultOvers <= 0) defaultOvers = 20;
        if (startDelaySeconds < 0) startDelaySeconds = 45;
    }
}
