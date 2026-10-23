package com.crease.live.simulation;

import com.crease.live.domain.Enums.Role;
import com.crease.live.domain.Player;
import com.crease.live.domain.Team;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** Generates fictional franchise squads (deterministic for a given seed). */
public final class SquadFactory {

    public record Franchise(String name, String shortName, String color, String venue) {
    }

    public static final List<Franchise> FRANCHISES = List.of(
            new Franchise("Pune Pathfinders", "PUN", "#7c5cff", "MCA Stadium, Pune"),
            new Franchise("Mumbai Monsoons", "MUM", "#1e88e5", "Wankhede Stadium, Mumbai"),
            new Franchise("Bengaluru Blazers", "BLR", "#e53935", "M. Chinnaswamy Stadium, Bengaluru"),
            new Franchise("Kolkata Comets", "KOL", "#c0ca33", "Eden Gardens, Kolkata"),
            new Franchise("Hyderabad Hawks", "HYD", "#fb8c00", "Rajiv Gandhi Intl. Stadium, Hyderabad"),
            new Franchise("Jaipur Jaguars", "JAI", "#d81b60", "Sawai Mansingh Stadium, Jaipur"));

    private static final List<Role> LINEUP = List.of(
            Role.BATTER, Role.BATTER, Role.BATTER, Role.WICKET_KEEPER, Role.BATTER,
            Role.ALL_ROUNDER, Role.ALL_ROUNDER, Role.BOWLER, Role.BOWLER, Role.BOWLER, Role.BOWLER);

    private static final List<String> FIRST = List.of(
            "Aarav", "Vihaan", "Arjun", "Kabir", "Rohan", "Ishaan", "Dev", "Aditya", "Siddharth", "Yash",
            "Karan", "Nikhil", "Pranav", "Rahul", "Varun", "Tanay", "Om", "Harsh", "Kunal", "Rishi",
            "Liam", "Noah", "Ethan", "Oliver", "Jack", "Mason", "Lucas", "Ravi", "Sameer", "Zaid",
            "Faisal", "Imran", "Daniel", "Josh", "Ben", "Tom", "Aiden", "Kiran", "Mihir", "Neel");
    private static final List<String> LAST = List.of(
            "Sharma", "Patil", "Iyer", "Deshmukh", "Kulkarni", "Reddy", "Nair", "Menon", "Joshi", "Gill",
            "Rao", "Bhat", "Chopra", "Malhotra", "Saxena", "Pawar", "Kapoor", "Banerjee", "Ghosh", "Sen",
            "Walker", "Hughes", "Fletcher", "Morgan", "Hayes", "Brooks", "Carter", "Khan", "Qureshi", "Siddiqui",
            "Fernando", "Perera", "Mendis", "Taylor", "Clarke", "Hale", "Gaikwad", "Thakur", "Mishra", "Chahal");

    private SquadFactory() {
    }

    public static List<Team> createAll(long seed) {
        Random rnd = new Random(seed);
        Set<String> used = new HashSet<>();
        List<Team> teams = new ArrayList<>();
        long teamId = 1;
        for (Franchise f : FRANCHISES) {
            List<Player> players = new ArrayList<>();
            for (int i = 0; i < LINEUP.size(); i++) {
                String name;
                do {
                    name = FIRST.get(rnd.nextInt(FIRST.size())) + " " + LAST.get(rnd.nextInt(LAST.size()));
                } while (!used.add(name));
                players.add(new Player(teamId * 100 + i + 1, name, LINEUP.get(i), teamId));
            }
            teams.add(new Team(teamId++, f.name(), f.shortName(), f.color(), Collections.unmodifiableList(players)));
        }
        return teams;
    }

    public static String homeVenue(Team t) {
        return FRANCHISES.stream().filter(f -> f.shortName().equals(t.shortName()))
                .map(Franchise::venue).findFirst().orElse("Neutral Venue");
    }
}
