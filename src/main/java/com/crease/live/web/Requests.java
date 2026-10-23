package com.crease.live.web;

import com.crease.live.domain.Enums.ExtraType;
import com.crease.live.domain.Enums.WicketType;
import com.crease.live.service.BallInput;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request bodies accepted by the API. */
public final class Requests {
    private Requests() {
    }

    public record CreateMatchRequest(
            @NotNull Long teamAId,
            @NotNull Long teamBId,
            @Min(1) @Max(50) Integer overs,
            @Size(max = 80) String venue,
            @Min(0) @Max(86_400) Integer startsInSeconds,
            Boolean autoSimulate) {
    }

    /** One delivery entered by a scorer or pushed by an external data provider. */
    public record BallRequest(
            @Min(0) @Max(7) int batRuns,
            ExtraType extraType,
            @Min(0) @Max(6) int extraRuns,
            WicketType wicketType,
            Long fielderId,
            Long bowlerId,
            @Size(max = 280) String commentary) {

        public BallInput toInput() {
            return new BallInput(batRuns, extraType, extraRuns, wicketType, fielderId, bowlerId, commentary);
        }
    }

    public record SimulationToggle(@NotNull Boolean enabled) {
    }
}
