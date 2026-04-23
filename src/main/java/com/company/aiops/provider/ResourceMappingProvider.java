package com.company.aiops.provider;

public interface ResourceMappingProvider {

    ResourceMapping queryResourceMapping(String serviceName, String clusterName);
}
