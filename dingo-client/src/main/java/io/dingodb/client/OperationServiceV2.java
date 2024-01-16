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

package io.dingodb.client;

import com.google.common.collect.ImmutableList;
import io.dingodb.client.common.Key;
import io.dingodb.client.common.Record;
import io.dingodb.client.operation.impl.DeleteRangeResult;
import io.dingodb.client.operation.impl.OpKeyRange;
import io.dingodb.client.utils.OperationUtils;
import io.dingodb.common.CommonId;
import io.dingodb.common.Location;
import io.dingodb.common.config.DingoConfiguration;
import io.dingodb.common.partition.RangeDistribution;
import io.dingodb.common.type.DingoType;
import io.dingodb.common.type.DingoTypeFactory;
import io.dingodb.common.type.scalar.LongType;
import io.dingodb.common.util.ByteArrayUtils;
import io.dingodb.common.util.Optional;
import io.dingodb.exec.base.IdGenerator;
import io.dingodb.exec.base.Job;
import io.dingodb.exec.base.OutputHint;
import io.dingodb.exec.base.Task;
import io.dingodb.exec.dag.Edge;
import io.dingodb.exec.dag.Vertex;
import io.dingodb.exec.impl.IdGeneratorImpl;
import io.dingodb.exec.impl.JobManagerImpl;
import io.dingodb.exec.operator.params.CoalesceParam;
import io.dingodb.exec.operator.params.CompareAndSetParam;
import io.dingodb.exec.operator.params.GetByKeysParam;
import io.dingodb.exec.operator.params.PartDeleteParam;
import io.dingodb.exec.operator.params.PartInsertParam;
import io.dingodb.exec.operator.params.PartRangeDeleteParam;
import io.dingodb.exec.operator.params.PartRangeScanParam;
import io.dingodb.exec.operator.params.PartitionParam;
import io.dingodb.exec.operator.params.RootParam;
import io.dingodb.exec.operator.params.SumUpParam;
import io.dingodb.exec.operator.params.ValuesParam;
import io.dingodb.meta.MetaService;
import io.dingodb.meta.entity.Column;
import io.dingodb.meta.entity.Table;
import io.dingodb.partition.DingoPartitionServiceProvider;
import io.dingodb.partition.PartitionService;
import io.dingodb.sdk.common.utils.Parameters;
import io.dingodb.store.proxy.service.CodecService;
import io.dingodb.store.proxy.service.TsoService;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static io.dingodb.client.utils.OperationUtils.mapKey2;
import static io.dingodb.client.utils.OperationUtils.mapKeyPrefix;
import static io.dingodb.common.util.NoBreakFunctions.wrap;
import static io.dingodb.common.util.Utils.sole;
import static io.dingodb.exec.utils.OperatorCodeUtils.COALESCE;
import static io.dingodb.exec.utils.OperatorCodeUtils.COMPARE_AND_SET;
import static io.dingodb.exec.utils.OperatorCodeUtils.GET_BY_KEYS;
import static io.dingodb.exec.utils.OperatorCodeUtils.PARTITION;
import static io.dingodb.exec.utils.OperatorCodeUtils.PART_DELETE;
import static io.dingodb.exec.utils.OperatorCodeUtils.PART_INSERT;
import static io.dingodb.exec.utils.OperatorCodeUtils.PART_RANGE_DELETE;
import static io.dingodb.exec.utils.OperatorCodeUtils.PART_RANGE_SCAN;
import static io.dingodb.exec.utils.OperatorCodeUtils.ROOT;
import static io.dingodb.exec.utils.OperatorCodeUtils.SUM_UP;
import static io.dingodb.exec.utils.OperatorCodeUtils.VALUES;

@Slf4j
public class OperationServiceV2 {
    private final MetaService metaService;
    private JobManagerImpl jobManager;

    public OperationServiceV2(String coordinatorSvr) {
        DingoConfiguration.instance().getConfigMap("store").put("coordinators", coordinatorSvr);
        metaService = io.dingodb.store.proxy.service.MetaService.ROOT;
        jobManager = JobManagerImpl.INSTANCE;
    }

    public void close() {

    }

//    public synchronized boolean createTable(String schema, String name, TableDefinition table) {
//        MetaService service = getSubMetaService(schema);
//        Optional.ifPresent(table.getPartDefinition(), __ -> checkAndConvertRangePartition(table));
//        service.createTable(name, Mapping.mapping(table));
//        return true;
//    }
//
//    private void checkAndConvertRangePartition(Table table) {
//        List<Column> columns = table.getColumns();
//        List<String> keyNames = new ArrayList<>();
//        List<DingoType> keyTypes = new ArrayList<>();
//        columns.stream().filter(Column::isPrimary)
//            .sorted(Comparator.comparingInt(Column::getPrimary))
//            .peek(col -> keyNames.add(col.getName()))
//            .map(col -> DingoTypeFactory.INSTANCE.fromName(col.getType(), col.getElementType(), col.isNullable()))
//            .forEach(keyTypes::add);
//        DefinitionUtils.checkAndConvertRangePartition(
//            keyNames,
//            table.getPartition().getCols(),
//            keyTypes,
//            table.getPartition().getDetails().stream().map(PartitionDetail::getOperand).collect(Collectors.toList())
//        );
//    }
//
//    public boolean dropTable(String schema, String tableName) {
//        MetaService service = getSubMetaService(schema);
//        return service.dropTable(tableName);
//    }

    public MetaService getSubMetaService(String schemaName) {
        schemaName = schemaName.toUpperCase();
        return Parameters.nonNull(metaService.getSubMetaService(schemaName), "Schema not found: " + schemaName);
    }

//    public TableDefinition getTableDefinition(String schemaName, String tableName) {
//        return getSubMetaService(schemaName).getTableDefinition(tableName);
//    }

    public Table getTable(String schema, String table) {
        return getSubMetaService(schema).getTable(table);
    }

    private long tso() {
        return TsoService.INSTANCE.tso();
    }

    public DeleteRangeResult rangeDelete(String schema, String tableName, Key begin, Key end, boolean withBegin, boolean withEnd) {
        long jobSeqId = tso();
        Job job = jobManager.createJob(jobSeqId, jobSeqId, CommonId.EMPTY_TRANSACTION, null);
        IdGeneratorImpl idGenerator = new IdGeneratorImpl(job.getJobId().seq);

        CommonId jobId = job.getJobId();
        try {
            Location currentLocation = MetaService.root().currentLocation();
            // rangeDelete --> root
            List<Vertex> rangeDeleteOutputs = rangeDelete(schema, tableName, job, idGenerator, currentLocation, begin, end, withBegin, withEnd);
            List<Vertex> root = root(job, idGenerator, currentLocation, rangeDeleteOutputs);
            if (root.size() > 0) {
                throw new IllegalStateException("There root of plan must be `DingoRoot`");
            }

            Iterator<Object[]> iterator = jobManager.createIterator(job, null);
            Long count = 0L;
            while (iterator.hasNext()) {
                count += (Long) iterator.next()[0];
            }
            return new DeleteRangeResult(count, null);
        } finally {
            jobManager.removeJob(jobId);
        }
    }

    public List<Record> get(String schema, String tableName, List<Key> keys) {
        long jobSeqId = tso();
        Job job = jobManager.createJob(jobSeqId, jobSeqId, CommonId.EMPTY_TRANSACTION, null);
        IdGeneratorImpl idGenerator = new IdGeneratorImpl(job.getJobId().seq);

        CommonId jobId = job.getJobId();
        MetaService metaService = getSubMetaService(schema);
        Table table = Parameters.nonNull(metaService.getTable(tableName), "Table not found.");
        List<Column> columns = table.getColumns();
        List<Object[]> tuples = keys.stream()
            .map(k -> mapKey2(k.getUserKey().toArray(), new Object[columns.size()], columns, table.keyColumns()))
            .collect(Collectors.toList());

        try {
            Location currentLocation = MetaService.root().currentLocation();
            // getByKey --> root
            List<Vertex> byKeyOutputs = getByKey(schema, tableName, job, idGenerator, currentLocation, tuples);
            List<Vertex> root = root(job, idGenerator, currentLocation, byKeyOutputs);
            if (root.size() > 0) {
                throw new IllegalStateException("There root of plan must be `DingoRoot`");
            }

            Iterator<Object[]> iterator = jobManager.createIterator(job, null);
            List<Record> records = new ArrayList<>();
            while (iterator.hasNext()) {
                Object[] next = iterator.next();
                records.add(new Record(next, table.getColumns()));
            }
            return records;
        } finally {
            jobManager.removeJob(jobId);
        }
    }

    public Boolean[] delete(String schema, String tableName, List<Key> keys) {
        long jobSeqId = tso();
        Job job = jobManager.createJob(jobSeqId, jobSeqId, CommonId.EMPTY_TRANSACTION, null);
        IdGeneratorImpl idGenerator = new IdGeneratorImpl(job.getJobId().seq);

        CommonId jobId = job.getJobId();

        MetaService metaService = getSubMetaService(schema);
        Table table = Parameters.nonNull(metaService.getTable(tableName), "Table not found.");
        List<Column> columns = table.getColumns();
        List<Object[]> tuples = keys.stream()
            .map(k ->
                mapKey2(k.getUserKey().toArray(), new Object[columns.size()], columns, table.keyColumns()))
            .collect(Collectors.toList());
        try {
            Location currentLocation = MetaService.root().currentLocation();
            // values --> delete --> coalesce --> sumUp --> root
            List<Vertex> valuesOutputs = values(schema, tableName, job, idGenerator, currentLocation, tuples);
            List<Vertex> deleteOutputs = delete(schema, tableName, idGenerator, currentLocation, valuesOutputs);
            List<Vertex> coalesceOutputs = coalesce(idGenerator, deleteOutputs);
            List<Vertex> root = root(job, idGenerator, currentLocation, coalesceOutputs);
            if (root.size() > 0) {
                throw new IllegalStateException("There root of plan must be `DingoRoot`");
            }

            Iterator<Object[]> iterator = jobManager.createIterator(job, null);
            Boolean[] keyState = null;
            while (iterator.hasNext()) {
                Boolean[] state = (Boolean[]) iterator.next()[1];
                if (keyState != null) {
                    Boolean[] dest = new Boolean[keyState.length + state.length];
                    System.arraycopy(keyState, 0, dest, 0, keyState.length);
                    System.arraycopy(state, 0, dest, keyState.length, state.length);
                    keyState = dest;
                }
                keyState = state;
            }
            return keyState;
        } finally {
            jobManager.removeJob(jobId);
        }
    }

    public Boolean[] insert(String schema, String tableName, List<Object[]> tuples) {
        long jobSeqId = tso();
        Job job = jobManager.createJob(jobSeqId, jobSeqId, CommonId.EMPTY_TRANSACTION, null);
        IdGenerator idGenerator = new IdGeneratorImpl(job.getJobId().seq);

        CommonId jobId = job.getJobId();
        try {
            Location currentLocation = MetaService.root().currentLocation();
            // values --> partition --> insert --> root
            List<Vertex> valuesOutputs = values(schema, tableName, job, idGenerator, currentLocation, tuples);
            List<Vertex> partitionOutputs = partition(schema, tableName, job, idGenerator, currentLocation, valuesOutputs);
            List<Vertex> insertOutputs = insert(schema, tableName, idGenerator, currentLocation, partitionOutputs);
            List<Vertex> root = root(job, idGenerator, currentLocation, insertOutputs);
            if (root.size() > 0) {
                throw new IllegalStateException("There root of plan must be `DingoRoot`");
            }

            Iterator<Object[]> iterator = jobManager.createIterator(job, null);
            Boolean[] keyState = null;
            while (iterator.hasNext()) {
                Boolean[] state = (Boolean[]) iterator.next()[1];
                if (keyState != null) {
                    Boolean[] dest = new Boolean[keyState.length + state.length];
                    System.arraycopy(keyState, 0, dest, 0, keyState.length);
                    System.arraycopy(state, 0, dest, keyState.length, state.length);
                    keyState = dest;
                }
                keyState = state;
            }
            return keyState;
        } finally {
            jobManager.removeJob(jobId);
        }
    }

    public Boolean[] compareAndSet(String schema, String tableName, List<Object[]> tuples, List<Object[]> expects) {
        long jobSeqId = tso();
        Job job = jobManager.createJob(jobSeqId, jobSeqId, CommonId.EMPTY_TRANSACTION, null);
        IdGeneratorImpl idGenerator = new IdGeneratorImpl(job.getJobId().seq);

        CommonId jobId = job.getJobId();
        List<Object[]> newTuples = new ArrayList<>();
        for (int i = 0; i < tuples.size(); i++) {
            Object[] tuple = tuples.get(i);
            Object[] expect = expects.get(i);
            Object[] newTuple = new Object[tuple.length + expect.length];
            System.arraycopy(tuple, 0, newTuple, 0, tuple.length);
            System.arraycopy(expect, 0, newTuple, tuple.length, expect.length);
            newTuples.add(newTuple);
        }
        try {
            // values --> partition --> compareAndSet --> root
            Location currentLocation = MetaService.root().currentLocation();
            List<Vertex> valuesOutputs = values(schema, tableName, job, idGenerator, currentLocation, newTuples);
            List<Vertex> partitionOutputs = partition(schema, tableName, job, idGenerator, currentLocation, valuesOutputs);
            List<Vertex> compareAndSetOutputs = compareAndSet(schema, tableName, idGenerator, currentLocation, partitionOutputs);
            List<Vertex> root = root(job, idGenerator, currentLocation, compareAndSetOutputs);
            if (root.size() > 0) {
                throw new IllegalStateException("There root of plan must be `DingoRoot`");
            }

            Iterator<Object[]> iterator = jobManager.createIterator(job, null);
            Boolean[] keyState = null;
            while (iterator.hasNext()) {
                Boolean[] state = (Boolean[]) iterator.next()[1];
                if (keyState != null) {
                    Boolean[] dest = new Boolean[keyState.length + state.length];
                    System.arraycopy(keyState, 0, dest, 0, keyState.length);
                    System.arraycopy(state, 0, dest, keyState.length, state.length);
                    keyState = dest;
                }
                keyState = state;
            }
            return keyState;
        } finally {
            jobManager.removeJob(jobId);
        }
    }

    public Iterator<Record> scan(String schema, String tableName, OpKeyRange keyRange) {
        long jobSeqId = tso();
        Job job = jobManager.createJob(jobSeqId, jobSeqId, CommonId.EMPTY_TRANSACTION, null);
        IdGenerator idGenerator = new IdGeneratorImpl(job.getJobId().seq);

        CommonId jobId = job.getJobId();
        try {
            MetaService metaService = getSubMetaService(schema);
            Table table = Parameters.nonNull(metaService.getTable(tableName), "Table not found.");
            Location currentLocation = MetaService.root().currentLocation();
            // scan --> coalesce --> root
            List<Vertex> scanOutputs = scan(schema, tableName, job, idGenerator, currentLocation, keyRange);
            List<Vertex> coalesceOutputs = coalesce(idGenerator, scanOutputs);
            List<Vertex> root = root(job, idGenerator, currentLocation, coalesceOutputs);
            if (root.size() > 0) {
                throw new IllegalStateException("There root of plan must be `DingoRoot`");
            }

            Iterator<Object[]> iterator = jobManager.createIterator(job, null);
            List<Record> records = new ArrayList<>();
            while (iterator.hasNext()) {
                Object[] next = iterator.next();
                records.add(new Record(next, table.getColumns()));
            }
            return records.iterator();
        } finally {
            jobManager.removeJob(jobId);
        }
    }

    private List<Vertex> scan(String schema, String tableName, Job job, IdGenerator idGenerator, Location currentLocation, OpKeyRange keyRange) {
        MetaService metaService = getSubMetaService(schema);
        Table td = Parameters.nonNull(metaService.getTable(tableName), "Table not found.");
        CommonId tableId = Parameters.nonNull(metaService.getTable(tableName).getTableId(), "Table not found.");
        final PartitionService ps = PartitionService.getService(
            Optional.ofNullable(td.getPartitionStrategy())
                .orElse(DingoPartitionServiceProvider.RANGE_FUNC_NAME));
        io.dingodb.codec.KeyValueCodec codec = CodecService.INSTANCE.createKeyValueCodec(
            tableId, td.tupleType(), td.keyMapping()
        );
        NavigableSet<io.dingodb.common.partition.RangeDistribution> distributions;

        Key start = keyRange.start;
        Object[] dst = new Object[td.getColumns().size()];
        byte[] startBytes = codec.encodeKey(mapKey2(start.getUserKey().toArray(), dst, td.getColumns(),
            start.columnOrder ? td.keyColumns() : OperationUtils.sortColumns(td.keyColumns())));
        Key end = keyRange.end;
        Object[] dst1 = new Object[td.getColumns().size()];
        byte[] endBytes = codec.encodeKey(mapKey2(end.getUserKey().toArray(), dst1, td.getColumns(),
            end.columnOrder ? td.keyColumns() : OperationUtils.sortColumns(td.keyColumns())));

        distributions = ps.calcPartitionRange(startBytes, endBytes, keyRange.withStart, keyRange.withEnd,
            MetaService.root().getRangeDistribution(tableId));

        List<Vertex> outputs = new ArrayList<>();
        for (io.dingodb.common.partition.RangeDistribution rd : distributions) {
            Task task = job.getOrCreate(currentLocation, idGenerator);
            PartRangeScanParam param = new PartRangeScanParam(
                tableId,
                rd.id(),
                td.tupleType(),
                td.keyMapping(),
                null,
                null,
                rd.getStartKey(),
                rd.getEndKey(),
                rd.isWithStart(),
                rd.isWithEnd(),
                null,
                null,
                td.tupleType(),
                true
            );
            Vertex vertex = new Vertex(PART_RANGE_SCAN, param);
            OutputHint hint = new OutputHint();
            hint.setPartId(rd.id());
            vertex.setHint(hint);
            vertex.setId(idGenerator.getOperatorId(task.getId()));
            task.putVertex(vertex);
            outputs.add(vertex);
        }
        return outputs;
    }

    private List<Vertex> rangeDelete(String schema, String tableName,
                                    Job job, IdGenerator idGenerator,
                                    Location currentLocation,
                                    Key begin, Key end, boolean withBegin, boolean withEnd) {
        MetaService metaService = getSubMetaService(schema);
        CommonId tableId = Parameters.nonNull(metaService.getTable(tableName).getTableId(), "Table not found.");
        Table td = Parameters.nonNull(metaService.getTable(tableName), "Table not found.");
        io.dingodb.codec.KeyValueCodec codec = CodecService.INSTANCE.createKeyValueCodec(
            tableId, td.tupleType(), td.keyMapping()
        );

        byte[] startKey = codec.encodeKeyPrefix(mapKeyPrefix(td, begin), begin.userKey.size());
        byte[] endKey = codec.encodeKeyPrefix(mapKeyPrefix(td, end), end.userKey.size());
        PartitionService ps = PartitionService.getService(
            Optional.ofNullable(td.getPartitionStrategy())
                .orElse(DingoPartitionServiceProvider.RANGE_FUNC_NAME));
        NavigableSet<io.dingodb.common.partition.RangeDistribution> distributions =
            ps.calcPartitionRange(
                startKey, endKey, withBegin, withEnd,
                MetaService.root().getRangeDistribution(tableId));
        List<Vertex> outputs = new ArrayList<>();
        for (io.dingodb.common.partition.RangeDistribution rd : distributions) {
            PartRangeDeleteParam param = new PartRangeDeleteParam(
                tableId,
                rd.id(),
                td.tupleType(),
                td.keyMapping(),
                rd.getStartKey(),
                rd.getEndKey(),
                rd.isWithStart(),
                rd.isWithEnd());
            Vertex vertex = new Vertex(PART_RANGE_DELETE, param);
            Task task = job.getOrCreate(currentLocation, idGenerator);
            vertex.setId(idGenerator.getOperatorId(task.getId()));
            task.putVertex(vertex);
            OutputHint hint = new OutputHint();
            hint.setToSumUp(true);
            vertex.setHint(hint);
            outputs.add(vertex);
        }
        return outputs;
    }

    private List<Vertex> values(String schema, String tableName, Job job, IdGenerator idGenerator, Location currentLocation, List<Object[]> tuples) {
        MetaService metaService = getSubMetaService(schema);
        Table td = Parameters.nonNull(metaService.getTable(tableName), "Table not found.");
        CommonId tableId = td.getTableId();
        PartitionService ps = PartitionService.getService(
            Optional.ofNullable(td.getPartitionStrategy()).orElse(DingoPartitionServiceProvider.RANGE_FUNC_NAME));
        io.dingodb.codec.KeyValueCodec codec = CodecService.INSTANCE.createKeyValueCodec(
            td.tupleType(), td.keyMapping()
        );
        NavigableMap<ByteArrayUtils.ComparableByteArray, RangeDistribution> parts = metaService.getRangeDistribution(tableId);

        List<Vertex> outputs = new LinkedList<>();
        Map<CommonId, List<Object[]>> partMap = ps.partTuples(tuples, wrap(codec::encodeKey), parts);
        for (Map.Entry<CommonId, List<Object[]>> entry : partMap.entrySet()) {
            ValuesParam valuesParam = new ValuesParam(entry.getValue(), null);
            Vertex values = new Vertex(VALUES, valuesParam);
            Task task = job.getOrCreate(currentLocation, idGenerator);
            values.setId(idGenerator.getOperatorId(task.getId()));
            OutputHint hint = new OutputHint();
            hint.setPartId(entry.getKey());
            hint.setLocation(currentLocation);
            values.setHint(hint);
            task.putVertex(values);

            outputs.add(values);
        }
        return outputs;
    }

    private List<Vertex> getByKey(String schema, String tableName, Job job, IdGenerator idGenerator, Location currentLocation, List<Object[]> tuples) {
        MetaService metaService = getSubMetaService(schema);
        Table td = Parameters.nonNull(metaService.getTable(tableName), "Table not found.");
        CommonId tableId = td.getTableId();
        PartitionService ps = PartitionService.getService(
            Optional.ofNullable(td.getPartitionStrategy())
                .orElse(DingoPartitionServiceProvider.RANGE_FUNC_NAME));
        NavigableMap<ByteArrayUtils.ComparableByteArray, RangeDistribution> parts = metaService.getRangeDistribution(tableId);
        io.dingodb.codec.KeyValueCodec codec = CodecService.INSTANCE.createKeyValueCodec(
            td.tupleType(), td.keyMapping()
        );
        Map<CommonId, List<Object[]>> partMap = ps.partTuples(tuples, wrap(codec::encodeKey), parts);

        List<Vertex> outputs = new LinkedList<>();
        for (Map.Entry<CommonId, List<Object[]>> entry : partMap.entrySet()) {
            GetByKeysParam param = new GetByKeysParam(
                tableId, entry.getKey(), td.tupleType(), td.keyMapping(), entry.getValue(), null, null
            );
            Task task = job.getOrCreate(currentLocation, idGenerator);
            Vertex vertex = new Vertex(GET_BY_KEYS, param);
            OutputHint hint = new OutputHint();
            hint.setPartId(entry.getKey());
            vertex.setHint(hint);
            vertex.setId(idGenerator.getOperatorId(task.getId()));
            task.putVertex(vertex);
            outputs.add(vertex);
        }
        return outputs;
    }

    private List<Vertex> partition(String schema, String tableName, Job job, IdGenerator idGenerator, Location currentLocation, List<Vertex> inputs) {
        List<Vertex> outpus = new LinkedList<>();
        MetaService metaService = getSubMetaService(schema);
        Table td = Parameters.nonNull(metaService.getTable(tableName), "Table not found.");
        CommonId tableId = td.getTableId();
        NavigableMap<ByteArrayUtils.ComparableByteArray, RangeDistribution> parts = metaService.getRangeDistribution(tableId);
        for (Vertex input : inputs) {
            Task task = input.getTask();
            PartitionParam partitionParam = new PartitionParam(parts, td);
            Vertex vertex = new Vertex(PARTITION, partitionParam);
            vertex.setId(idGenerator.getOperatorId(task.getId()));
            OutputHint hint = new OutputHint();
            hint.setLocation(currentLocation);
            vertex.setHint(hint);
            Edge edge = new Edge(input, vertex);
            vertex.addIn(edge);
            task.putVertex(vertex);
            input.addEdge(edge);
            outpus.add(vertex);
        }
        return outpus;
    }

    private List<Vertex> coalesce( IdGenerator idGenerator, List<Vertex> inputs) {
        Map<CommonId, List<Vertex>> inputsMap = new HashMap<>();
        for (Vertex input : inputs) {
            CommonId taskId = input.getTaskId();
            List<Vertex> list = inputsMap.computeIfAbsent(taskId, k -> new LinkedList<>());
            list.add(input);
        }
        List<Vertex> outputs = new LinkedList<>();
        for (Map.Entry<CommonId, List<Vertex>> entry : inputsMap.entrySet()) {
            List<Vertex> list = entry.getValue();
            int size = list.size();
            if (size <= 1) {
                // Need no coalescing.
                outputs.addAll(list);
            } else {
                Vertex one = list.get(0);
                Task task = one.getTask();
                CoalesceParam coalesceParam = new CoalesceParam(size);
                Vertex coalesce = new Vertex(COALESCE, coalesceParam);
                coalesce.setId(idGenerator.getOperatorId(task.getId()));
                task.putVertex(coalesce);
                int i = 0;
                for (Vertex input : list) {
                    input.addEdge(new Edge(input, coalesce));
                    input.setPin(i);
                    ++i;
                }
                coalesce.copyHint(one);
                Edge edge = new Edge(one, coalesce);
                coalesce.addIn(edge);
                if (one.isToSumUp()) {
                    SumUpParam sumUpParam = new SumUpParam();
                    Vertex sumUp = new Vertex(SUM_UP, sumUpParam);
                    sumUp.setId(idGenerator.getOperatorId(task.getId()));
                    task.putVertex(sumUp);
                    sumUp.copyHint(coalesce);
                    Edge sumUpEdge = new Edge(coalesce, sumUp);
                    coalesce.addEdge(sumUpEdge);
                    sumUp.addIn(sumUpEdge);
                    outputs.add(sumUp);
                } else {
                    outputs.add(coalesce);
                }
            }
        }
        return outputs;
    }

    private List<Vertex> delete(String schema, String tableName, IdGenerator idGenerator, Location currentLocation, List<Vertex> inputs) {
        MetaService metaService = getSubMetaService(schema);
        CommonId tableId = Parameters.nonNull(metaService.getTable(tableName).getTableId(), "Table not found.");
        Table td = Parameters.nonNull(metaService.getTable(tableName), "Table not found.");
        List<Vertex> outputs = new LinkedList<>();
        NavigableMap<ByteArrayUtils.ComparableByteArray, RangeDistribution> parts = metaService.getRangeDistribution(tableId);

        for (Vertex input : inputs) {
            Task task = input.getTask();
            PartDeleteParam param = new PartDeleteParam(tableId, input.getHint().getPartId(),
                td.tupleType(), td.keyMapping(), td, parts);
            Vertex vertex = new Vertex(PART_DELETE, param);
            vertex.setId(idGenerator.getOperatorId(task.getId()));
            task.putVertex(vertex);
            input.setPin(0);
            OutputHint hint = new OutputHint();
            hint.setToSumUp(true);
            vertex.setHint(hint);
            Edge edge = new Edge(input, vertex);
            input.addEdge(edge);
            vertex.addIn(edge);
            outputs.add(vertex);
        }
        return outputs;
    }

    private List<Vertex> insert(String schema, String tableName, IdGenerator idGenerator, Location currentLocation, List<Vertex> inputs) {
        MetaService metaService = getSubMetaService(schema);
        CommonId tableId = Parameters.nonNull(metaService.getTable(tableName).getTableId(), "Table not found.");
        Table tableDefinition = Parameters.nonNull(metaService.getTable(tableName), "Table not found.");
        List<Vertex> outputs = new LinkedList<>();
        NavigableMap<ByteArrayUtils.ComparableByteArray, RangeDistribution> parts = metaService.getRangeDistribution(tableId);

        for (Vertex input : inputs) {
            Task task = input.getTask();
            PartInsertParam insertParam = new PartInsertParam(tableId, input.getHint().getPartId(),
                tableDefinition.tupleType(), tableDefinition.keyMapping(), tableDefinition, parts);
            Vertex vertex = new Vertex(PART_INSERT, insertParam);
            vertex.setId(idGenerator.getOperatorId(task.getId()));
            task.putVertex(vertex);
            input.setPin(0);
            OutputHint hint = new OutputHint();
            hint.setToSumUp(false);
            vertex.setHint(hint);
            Edge edge = new Edge(input, vertex);
            input.addEdge(edge);
            vertex.addIn(edge);
            outputs.add(vertex);
        }
        return outputs;
    }

    private List<Vertex> compareAndSet(String schema, String tableName, IdGenerator idGenerator, Location currentLocation, List<Vertex> inputs) {
        MetaService metaService = getSubMetaService(schema);
        CommonId tableId = Parameters.nonNull(metaService.getTable(tableName).getTableId(), "Table not found.");
        Table td = Parameters.nonNull(metaService.getTable(tableName), "Table not found.");
        NavigableMap<ByteArrayUtils.ComparableByteArray, io.dingodb.common.partition.RangeDistribution> parts = new TreeMap<>();
        MetaService.root().getRangeDistribution(tableId)
            .forEach((k, v) -> parts.put(new ByteArrayUtils.ComparableByteArray(k.getBytes(), k.isIgnoreLen(), k.getPos()), v));

        List<Vertex> outputs = new LinkedList<>();

        for (Vertex input : inputs) {
            Task task = input.getTask();
            CompareAndSetParam param = new CompareAndSetParam(tableId,
                input.getHint().getPartId(), td.tupleType(), td.keyMapping(), td, parts);
            Vertex vertex = new Vertex(COMPARE_AND_SET, param);
            vertex.setId(idGenerator.getOperatorId(task.getId()));
            task.putVertex(vertex);
            input.setPin(0);
            Edge edge = new Edge(input, vertex);
            input.addEdge(edge);
            vertex.addIn(edge);
            outputs.add(vertex);
        }
        return outputs;
    }

    private List<Vertex> root(Job job, IdGenerator idGenerator, Location currentLocation, List<Vertex> inputs) {
        if (inputs.size() != 1) {
            throw new IllegalStateException("There must be one input to job root");
        }
        Vertex input = sole(inputs);
        DingoType dingoType = new LongType(false);
        RootParam param = new RootParam(DingoTypeFactory.tuple(new DingoType[]{dingoType}), null);
        Vertex vertex = new Vertex(ROOT, param);
        Task task = input.getTask();
        CommonId id = idGenerator.getOperatorId(task.getId());
        vertex.setId(id);
        Edge edge = new Edge(input, vertex);
        input.addEdge(edge);
        vertex.addIn(edge);
        task.putVertex(vertex);
        task.markRoot(id);
        job.markRoot(task.getId());
        return ImmutableList.of();
    }

}
