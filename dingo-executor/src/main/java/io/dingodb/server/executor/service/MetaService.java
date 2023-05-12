/*
 * Copyright 2021 DataCanvas
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.dingodb.server.executor.service;

import com.google.auto.service.AutoService;
import io.dingodb.common.CommonId;
import io.dingodb.common.config.DingoConfiguration;
import io.dingodb.common.config.VariableConfiguration;
import io.dingodb.common.mysql.scope.ScopeVariables;
import io.dingodb.common.partition.RangeDistribution;
import io.dingodb.common.table.TableDefinition;
import io.dingodb.common.util.ByteArrayUtils.ComparableByteArray;
import io.dingodb.meta.Meta;
import io.dingodb.meta.MetaServiceProvider;
import io.dingodb.meta.TableStatistic;
import io.dingodb.sdk.common.table.Table;
import io.dingodb.common.util.Optional;
import io.dingodb.sdk.common.DingoCommonId;
import io.dingodb.sdk.service.meta.MetaServiceClient;
import io.dingodb.server.executor.Configuration;
import io.dingodb.server.executor.common.Mapping;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static io.dingodb.common.CommonId.CommonType.SCHEMA;
import static io.dingodb.server.executor.common.Mapping.mapping;

public class MetaService implements io.dingodb.meta.MetaService {

    public static final MetaService ROOT = new MetaService(new MetaServiceClient(Configuration.coordinators()));

    @AutoService(MetaServiceProvider.class)
    public static class Provider implements MetaServiceProvider {
        @Override
        public io.dingodb.meta.MetaService root() {
            return ROOT;
        }
    }

    private MetaService(MetaServiceClient metaServiceClient) {
        this.metaServiceClient = metaServiceClient;
    }

    //
    // Meta service.
    //

    public static CommonId getParentSchemaId(CommonId tableId) {
        return new CommonId(SCHEMA, 0, tableId.domain);
    }

    protected final MetaServiceClient metaServiceClient;

    @Override
    public CommonId id() {
        // todo refactor
        Meta.DingoCommonId id = metaServiceClient.id();
        return new CommonId(
            CommonId.CommonType.of(id.getEntityType().getNumber()), id.getParentEntityId(), id.getEntityId()
        );
    }

    @Override
    public String name() {
        return metaServiceClient.name().toUpperCase();
    }

    @Override
    public void createSubMetaService(String name) {
        metaServiceClient.createSubMetaService(name);
    }

    @Override
    public Map<String, io.dingodb.meta.MetaService> getSubMetaServices() {
        return metaServiceClient.getSubMetaServices().values().stream()
            .collect(Collectors.toMap(MetaServiceClient::name, MetaService::new));
    }

    @Override
    public MetaService getSubMetaService(String name) {
        return new MetaService(metaServiceClient.getSubMetaService(name.toLowerCase()));
    }

    public MetaService getSubMetaService(CommonId id) {
        return new MetaService(metaServiceClient.getSubMetaService(mapping(id)));
    }

    @Override
    public boolean dropSubMetaService(String name) {
        return metaServiceClient.dropSubMetaService(name);
    }

    @Override
    public void createTable(@NonNull String tableName, @NonNull TableDefinition tableDefinition) {
        metaServiceClient.createTable(tableName, mapping(tableDefinition));
    }

    @Override
    public boolean dropTable(@NonNull String tableName) {
        return metaServiceClient.dropTable(tableName);
    }

    @Override
    public CommonId getTableId(@NonNull String tableName) {
        return mapping(metaServiceClient.getTableId(tableName));
    }

    @Override
    public Map<String, TableDefinition> getTableDefinitions() {
        return metaServiceClient.getTableDefinitions().values().stream()
            .collect(Collectors.toMap(Table::getName, Mapping::mapping));
    }

    @Override
    public TableDefinition getTableDefinition(@NonNull String name) {
        return mapping(metaServiceClient.getTableDefinition(name));
    }

    @Override
    public TableDefinition getTableDefinition(@NonNull CommonId id) {
        return mapping(metaServiceClient.getTableDefinition(mapping(id)));
    }

    public RangeDistribution getRangeDistribution(CommonId tableId, CommonId distributionId) {
        return getRangeDistribution(tableId).values().stream()
            .filter(d -> d.id().equals(distributionId))
            .findAny().get();
    }

    @Override
    public NavigableMap<ComparableByteArray, RangeDistribution> getRangeDistribution(CommonId id) {
        NavigableMap<ComparableByteArray, RangeDistribution> result = new TreeMap<>();
        metaServiceClient.getRangeDistribution(mapping(id)).forEach((k, v) -> result.put(mapping(k), mapping(v)));
        return result;
    }

    @Override
    public TableStatistic getTableStatistic(@NonNull String tableName) {
        return new io.dingodb.meta.TableStatistic() {

            @Override
            public byte[] getMinKey() {
                return metaServiceClient.getTableMetrics(tableName).getMinKey();
            }

            @Override
            public byte[] getMaxKey() {
                return metaServiceClient.getTableMetrics(tableName).getMaxKey();
            }

            @Override
            public long getPartCount() {
                return metaServiceClient.getTableMetrics(tableName).getPartCount();
            }

            @Override
            public Double getRowCount() {
                return (double) metaServiceClient.getTableMetrics(tableName).getRowCount();
            }
        };
    }

    @Override
    public Long getAutoIncrement(CommonId tableId) {
        DingoCommonId dingoCommonId = mapping(tableId);

        long count = Optional.ofNullable(DingoConfiguration.instance().getVariable())
            .map(VariableConfiguration::getAutoIncrementCacheCount)
            .orElse(100L);

        Integer increment = Optional.ofNullable(DingoConfiguration.instance().getVariable())
            .map(VariableConfiguration::getAutoIncrementIncrement)
            .ifAbsentSet(() -> Integer.valueOf(ScopeVariables.globalVariables.getProperty("auto_increment_increment")))
            .orElse(1);

        Integer offset = Optional.ofNullable(DingoConfiguration.instance().getVariable())
            .map(VariableConfiguration::getAutoIncrementOffset)
            .ifAbsentSet(() -> Integer.valueOf(ScopeVariables.globalVariables.getProperty("auto_increment_offset")))
            .orElse(1);

        metaServiceClient.generateAutoIncrement(dingoCommonId, count, increment, offset);
        return metaServiceClient.getIncrementId(dingoCommonId);
    }
}