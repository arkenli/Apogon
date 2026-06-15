package com.company.aiops.persistence.repository;

import com.company.aiops.provider.query.mapping.model.ProviderQueryMappingRecord;

import java.util.List;
import java.util.Optional;

/**
 * Reads and maintains provider-specific query target mappings.
 */
public interface ProviderQueryMappingRepository {

    List<ProviderQueryMappingRecord> findEnabledByServiceAndAlertCluster(
            String serviceName,
            String alertClusterName
    );

    List<ProviderQueryMappingRecord> findAll(
            String serviceName,
            String alertClusterName,
            String providerName
    );

    Optional<ProviderQueryMappingRecord> findById(long id);

    ProviderQueryMappingRecord create(ProviderQueryMappingRecord record);

    ProviderQueryMappingRecord update(ProviderQueryMappingRecord record);

    boolean deleteById(long id);
}
