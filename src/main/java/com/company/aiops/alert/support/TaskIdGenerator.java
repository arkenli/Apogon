package com.company.aiops.alert.support;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 生成分析任务 ID，便于按时间排查任务创建顺序。
 */
@Component
public class TaskIdGenerator {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");

    /**
     * 生成形如 task_yyyyMMddHHmmss_xxxxxxxx 的任务编号。
     */
    public String nextTaskId() {
        return "task_"
                + LocalDateTime.now(DEFAULT_ZONE).format(FORMATTER)
                + "_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
