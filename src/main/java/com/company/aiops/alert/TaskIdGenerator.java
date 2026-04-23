package com.company.aiops.alert;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Component
public class TaskIdGenerator {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");

    public String nextTaskId() {
        return "task_"
                + LocalDateTime.now(DEFAULT_ZONE).format(FORMATTER)
                + "_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
