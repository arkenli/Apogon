package com.company.aiops.provider.query;

import com.company.aiops.persistence.repository.ProviderQueryMappingRepository;
import com.company.aiops.provider.query.mapping.model.ProviderQueryMappingRecord;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Resolves provider query targets from enabled database mappings.
 */
@Component
public class DefaultProviderQueryTargetResolver implements ProviderQueryTargetResolver {

    private final ProviderQueryMappingRepository providerQueryMappingRepository;

    public DefaultProviderQueryTargetResolver(ProviderQueryMappingRepository providerQueryMappingRepository) {
        this.providerQueryMappingRepository = providerQueryMappingRepository;
    }

    @Override
    public ProviderQueryTargets resolveAll(
            String serviceName,
            String alertClusterName,
            Set<String> providerNames
    ) {
        Map<String, ProviderQueryTarget> targets = new LinkedHashMap<>();
        for (ProviderQueryMappingRecord record : providerQueryMappingRepository.findEnabledByServiceAndAlertCluster(
                serviceName,
                alertClusterName
        )) {
            if (!providerNames.contains(record.providerName())) {
                continue;
            }
            targets.putIfAbsent(
                    record.providerName(),
                    ProviderQueryTarget.mapped(
                            record.providerName(),
                            record.queryServiceName(),
                            record.queryClusterName()
                    )
            );
        }
        return new ProviderQueryTargets(serviceName, alertClusterName, targets);
    }
}
