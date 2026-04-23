package com.company.aiops.common.health;

import com.company.aiops.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;

@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    @GetMapping
    public ApiResponse<HealthResponse> health() {
        return ApiResponse.ok(new HealthResponse("UP", OffsetDateTime.now()));
    }

    public record HealthResponse(String status, OffsetDateTime checkedAt) {
    }
}
