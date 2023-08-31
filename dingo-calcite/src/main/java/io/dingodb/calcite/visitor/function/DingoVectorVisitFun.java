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

package io.dingodb.calcite.visitor.function;

import io.dingodb.calcite.DingoRelOptTable;
import io.dingodb.calcite.DingoTable;
import io.dingodb.calcite.rel.DingoVector;
import io.dingodb.calcite.visitor.DingoJobVisitor;
import io.dingodb.common.CommonId;
import io.dingodb.common.Location;
import io.dingodb.common.partition.RangeDistribution;
import io.dingodb.common.table.TableDefinition;
import io.dingodb.common.type.TupleMapping;
import io.dingodb.common.util.ByteArrayUtils.ComparableByteArray;
import io.dingodb.exec.base.IdGenerator;
import io.dingodb.exec.base.Job;
import io.dingodb.exec.base.Output;
import io.dingodb.exec.base.Task;
import io.dingodb.exec.operator.PartVectorOperator;
import io.dingodb.exec.partition.DingoPartitionStrategyFactory;
import io.dingodb.exec.partition.PartitionStrategy;
import io.dingodb.meta.MetaService;
import lombok.extern.slf4j.Slf4j;
import org.apache.calcite.sql.SqlBasicCall;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNumericLiteral;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;

@Slf4j
public final class DingoVectorVisitFun {

    private DingoVectorVisitFun() {
    }

    public static Collection<Output> visit(
        Job job, IdGenerator idGenerator, Location currentLocation, DingoJobVisitor visitor, DingoVector rel
    ) {
        DingoRelOptTable relTable = rel.getTable();
        DingoTable dingoTable = relTable.unwrap(DingoTable.class);

        MetaService metaService = MetaService.root().getSubMetaService(relTable.getSchemaName());
        CommonId tableId = dingoTable.getTableId();
        TableDefinition td = dingoTable.getTableDefinition();

        NavigableMap<ComparableByteArray, RangeDistribution> ranges = metaService.getRangeDistribution(tableId);
        PartitionStrategy<CommonId, byte[]> ps = DingoPartitionStrategyFactory.createPartitionStrategy(td, ranges);

        List<SqlNode> operandsList = rel.getOperands();

        List<SqlNode> operands = ((SqlBasicCall) operandsList.get(2)).getOperandList();
        Float[] floatArray = new Float[operands.size()];
        for (int i = 0; i < operands.size(); i++) {
            floatArray[i] = (
                (Number) Objects.requireNonNull(((SqlNumericLiteral) operands.get(i)).getValue())
            ).floatValue();
        }

        int topN = ((Number) Objects.requireNonNull(((SqlNumericLiteral) operandsList.get(3)).getValue())).intValue();

        List<Output> outputs = new ArrayList<>();

        // Get all index table distributions
        NavigableMap<ComparableByteArray, RangeDistribution> indexRangeDistribution =
            metaService.getIndexRangeDistribution(rel.getIndexTableId());

        int rowTypeSize = rel.getRowType().getFieldList().size();
        int[] select = new int[rowTypeSize];
        for (int i = 0; i < rowTypeSize; i++) {
            select[i] = i;
        }

        // Create tasks based on partitions
        for (RangeDistribution rangeDistribution : indexRangeDistribution.values()) {
            PartVectorOperator operator = new PartVectorOperator(
                tableId,
                rangeDistribution.id(),
                td.getDingoType(),
                td.getKeyMapping(),
                null,
                TupleMapping.of(select),
                td,
                ps,
                rel.getIndexTableId(),
                rangeDistribution.id(),
                floatArray,
                topN
            );
            operator.setId(idGenerator.get());
            Task task = job.getOrCreate(currentLocation, idGenerator);
            task.putOperator(operator);
            outputs.addAll(operator.getOutputs());
        }

        return outputs;
    }
}