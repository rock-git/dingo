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

package io.dingodb.calcite.rule;

import io.dingodb.calcite.rel.DingoLikeScan;
import io.dingodb.calcite.rel.DingoTableScan;
import io.dingodb.calcite.utils.RexLiteralUtils;
import io.dingodb.calcite.utils.RuleUtils;
import io.dingodb.common.codec.Codec;
import io.dingodb.common.codec.DingoCodec;
import io.dingodb.common.table.TableDefinition;
import io.dingodb.common.type.DingoTypeFactory;
import lombok.extern.slf4j.Slf4j;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelRule;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.type.SqlTypeName;
import org.immutables.value.Value;

import java.io.IOException;
import java.util.Collections;

import static io.dingodb.calcite.DingoTable.dingo;

@Slf4j
@Value.Enclosing
public class DingoLikeRule extends RelRule<DingoLikeRule.Config> {

    public DingoLikeRule(Config config) {
        super(config);
    }

    @Override
    public void onMatch(RelOptRuleCall call) {
        final DingoTableScan rel = call.rel(0);
        TableDefinition td = dingo(rel.getTable()).getTableDefinition();
        int firstPrimaryColumnIndex = td.getFirstPrimaryColumnIndex();
        RexCall filter = (RexCall) rel.getFilter();

        RexLiteral rexLiteral = (RexLiteral) filter.operands.get(1);
        RexLiteral prefix = getPrefix(rexLiteral);
        if (prefix == null) {
            log.warn("The prefix is empty, original filter string is {}", rexLiteral.toString());
            return;
        }

        RexInputRef rexInputRef = (RexInputRef) filter.operands.get(0);
        int index = rexInputRef.getIndex();
        if (index != firstPrimaryColumnIndex) {
            log.warn("The current field is not the primary key of the first column, "
                + "first primary column is {}, current column is {}", firstPrimaryColumnIndex, index);
            return;
        }

        Codec codec = new DingoCodec(Collections.singletonList(
            td.getColumn(firstPrimaryColumnIndex).getDingoType().toDingoSchema(0)), null, true);
        byte[] prefixBytes = null;
        try {
            prefixBytes = codec.encodeKeyForRangeScan(new Object[]{RexLiteralUtils.convertFromRexLiteral(
                prefix,
                DingoTypeFactory.fromRelDataType(prefix.getType())
            )});
        } catch (IOException e) {
            log.error("Some errors occurred in encodeKeyForRangeScan: ", e);
            throw new RuntimeException(e);
        }

        if (prefix.getTypeName() == SqlTypeName.CHAR) {
            byte lastByte = prefixBytes[prefixBytes.length - 1];
            if (lastByte < 0) {
                prefixBytes = RuleUtils.getBytesByInterceptingNBits(prefixBytes, (int) lastByte);
            }
        }

        call.transformTo(
            new DingoLikeScan(
                rel.getCluster(),
                rel.getTraitSet(),
                rel.getHints(),
                rel.getTable(),
                rel.getFilter(),
                rel.getSelection(),
                prefixBytes
            )
        );
    }

    @Value.Immutable
    public interface Config extends RelRule.Config {
        Config DEFAULT = ImmutableDingoLikeRule.Config.builder()
            .operandSupplier(
                b0 -> b0.operand(DingoTableScan.class)
                    .predicate(r -> {
                            if (r.getFilter() != null && r.getFilter() instanceof RexCall) {
                                RexCall filter = (RexCall) r.getFilter();
                                if (filter.getKind() == SqlKind.LIKE || filter.op.getName().equals("LIKE_BINARY")) {
                                    return true;
                                }
                            }
                            return false;
                        }
                    ).noInputs()
            )
            .description("DingoLikeRule")
            .build();

        @Override
        default DingoLikeRule toRule() {
            return new DingoLikeRule(this);
        }
    }

    static RexLiteral getPrefix(RexLiteral rexLiteral) {
        StringBuilder stringBuilder = new StringBuilder();
        // Process redundant ''
        String patternStr = rexLiteral.toString().replaceAll("\'", "");
        if (patternStr.trim().length() == 0) {
            return null;
        }

        char[] prefixChars = patternStr.toCharArray();
        // Record the previous one to determine whether the wildcard is escaped
        char previousChar = 0;
        for (char prefixChar : prefixChars) {
            // If it is % or _ or [, indicates a wildcard is encountered
            if (prefixChar == '%' || prefixChar == '_' || prefixChar == '[') {
                // If the previous bit is not '\\', the prefix has ended
                if (previousChar != '\\') {
                    break;
                }
            }
            previousChar = prefixChar;
            stringBuilder.append(prefixChar);
        }

        return RexLiteral.fromJdbcString(rexLiteral.getType(), rexLiteral.getTypeName(), stringBuilder.toString());
    }
}