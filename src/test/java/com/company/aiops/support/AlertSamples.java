package com.company.aiops.support;

/**
 * Canonical raw alert samples reused by parser, webhook, and replay tests.
 *
 * <p>The text intentionally stays aligned with the current WiseEye parser patterns so the phase-10
 * replay suite exercises the same payload shape that the earlier stages were built around.</p>
 */
public final class AlertSamples {

    public static final String RATE_ALERT = """
            WiseEye Alarm:
            Status: OPEN
            Severity: Major
            Host: CP_all_all_sevice_failrate_over70_ams:{"servicename":"ams_front","interfacename":"vmall-ams.PrizeActivityService.queryPrizeResult","cluster":"cluster1"}
            Category: AIOps@rate
            Details: CP_all_all_sevice_failrate_over70_ams 1、告警名称：CP域服务接口错误率连续1分钟大于70%
            2、告警对象：服务名称：ams_front,接口：vmall-ams.PrizeActivityService.queryPrizeResult,集群：cluster1
            3、告警信息：请求总数=3.0,失败率=100%, 发生时间=2026-04-20T18:09:00
            4、诊断信息：chat analysis vmall-service-errors-delimit-analysis serviceName='ams_front' interfaceName='vmall-ams.PrizeActivityService.queryPrizeResult' occurTime='2026-04-20T18:09:00'AlertName: CP域服务接口错误率连续1分钟大于70%,Site: cn_product_default,MetricName: CP_all_all_sevice_failrate_over70_ams,Category: AIOps@rate
            IP Address: null
            Occur Time: 2026-04-20 18:13:40+08:00
            Clear Time: null
            Template: unknown
            Handler: mWX1336861,w00836539
            Alert ID: 521600500
            Tenant: 电商
            Application: 电商业务
            Service: VmallActivityService
            Site: cn_product_default
            """;

    public static final String LATENCY_ALERT = """
            WiseEye Alarm:
            Status: OPEN
            Severity: Major
            Host: BP_ingresscache_service_delay_avg:{"servicename":"vmall_ingresscache_ica","interfacename":"persistent://WiseCloudDMQService/WiseCloudDMQService/MQTopic_PMS.refreshAppProduct_cluster2","cluster":"cluster2"}
            Category: AIOps@delay
            Details: BP_ingresscache_service_delay_avg
            1、告警名称：ingresscache接口平均时延超3s持续5分钟
            2、告警对象：服务名称vmall_ingresscache_ica，接口名称为persistent://WiseCloudDMQService/WiseCloudDMQService/MQTopic_PMS.refreshAppProduct_cluster2，集群：cluster2
            3、告警信息：平均时延=3067, 告警发生时间=2026-04-21T10:33:00, 请求总数=49.0
            4、诊断命令：chat analysis vmall-service-latency-delimit-analysis serviceName='vmall_ingresscache_ica' interfaceName='persistent://WiseCloudDMQService/WiseCloudDMQService/MQTopic_PMS.refreshAppProduct_cluster2' occurTime='2026-04-21T10:33:00'
            AlertName: ingresscache接口平均时延超3s持续5分钟,Site: cn_product_default,MetricName: BP_ingresscache_service_delay_avg,Category: AIOps@delay
            IP Address: null
            Occur Time: 2026-04-21 10:38:31+08:00
            Clear Time: null
            Template: unknown
            Handler: yWX1352312,y00666716
            Alert ID: 521932116
            Tenant: 电商
            Application: 电商业务
            Service: VmallOpenGWService
            Site: cn_product_default
            """;

    private AlertSamples() {
    }

    /**
     * Produces a replay payload with a different alert id while preserving the original sample
     * structure.
     */
    public static String withAlertId(String rawAlert, String alertId) {
        return rawAlert.replaceAll("(?m)^Alert ID: .+$", "Alert ID: " + alertId);
    }

    /**
     * Injects a service name into both the host JSON and the diagnostic command so provider and
     * mock-LLM token tests can steer deterministic branches without custom Spring wiring.
     */
    public static String withServiceName(String rawAlert, String serviceName) {
        return rawAlert
                .replaceAll("\"servicename\":\"[^\"]+\"", "\"servicename\":\"" + serviceName + "\"")
                .replaceAll("serviceName='[^']+'", "serviceName='" + serviceName + "'");
    }
}
