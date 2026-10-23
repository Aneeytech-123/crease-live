package com.crease.live;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Boots the full context with demo data but without the background simulator, then exercises the API. */
@SpringBootTest(properties = "cricket.simulation.enabled=false")
@AutoConfigureMockMvc
class CreaseLiveApplicationTests {

    @Autowired
    MockMvc mvc;

    @Test
    void listsSeededMatchesAndTeams() throws Exception {
        mvc.perform(get("/api/matches")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(4));
        mvc.perform(get("/api/teams")).andExpect(status().isOk()).andExpect(jsonPath("$[0].players.length()").value(11));
    }

    @Test
    void scorecardSummaryAndLeaderboardAreAvailable() throws Exception {
        mvc.perform(get("/api/matches/1001")).andExpect(status().isOk()).andExpect(jsonPath("$.innings.length()").value(2));
        mvc.perform(get("/api/matches/1003/summary")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        mvc.perform(get("/api/players/leaderboard?type=runs&limit=5")).andExpect(status().isOk());
    }

    @Test
    void manualScoringValidatesTheLaws() throws Exception {
        mvc.perform(post("/api/matches/1002/balls").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batRuns\":4}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ball.label").value("4"));
        mvc.perform(post("/api/matches/1002/balls").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batRuns\":2,\"extraType\":\"WIDE\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/matches/999999")).andExpect(status().isNotFound());
    }
}
