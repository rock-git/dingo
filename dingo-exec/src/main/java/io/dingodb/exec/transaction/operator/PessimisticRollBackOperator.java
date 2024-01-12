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

package io.dingodb.exec.transaction.operator;

import io.dingodb.common.CommonId;
import io.dingodb.common.codec.PrimitiveCodec;
import io.dingodb.common.store.KeyValue;
import io.dingodb.exec.Services;
import io.dingodb.exec.dag.Vertex;
import io.dingodb.exec.fin.Fin;
import io.dingodb.exec.fin.FinWithException;
import io.dingodb.exec.transaction.base.TransactionConfig;
import io.dingodb.exec.transaction.params.PessimisticRollBackParam;
import io.dingodb.exec.transaction.util.TransactionUtil;
import io.dingodb.exec.utils.ByteUtils;
import io.dingodb.store.api.StoreInstance;
import io.dingodb.store.api.transaction.data.IsolationLevel;
import io.dingodb.store.api.transaction.data.Op;
import io.dingodb.store.api.transaction.data.rollback.TxnPessimisticRollBack;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Map;


@Slf4j
public class PessimisticRollBackOperator extends TransactionOperator {
    public static final PessimisticRollBackOperator INSTANCE = new PessimisticRollBackOperator();

    private PessimisticRollBackOperator() {
    }

    @Override
    public synchronized boolean push(int pin, @Nullable Object[] tuple, Vertex vertex) {
        PessimisticRollBackParam param = vertex.getParam();
        CommonId txnId = (CommonId) tuple[0];
        long forUpdateTs = (long) tuple[1];
        CommonId tableId = (CommonId) tuple[2];
        CommonId newPartId = (CommonId) tuple[3];
        int op = (byte) tuple[4];
        byte[] key = (byte[]) tuple[5];
        byte[] value = (byte[]) tuple[6];
        StoreInstance store = Services.LOCAL_STORE.getInstance(tableId, newPartId);
        byte[] txnIdByte = txnId.encode();
        byte[] tableIdByte = tableId.encode();
        byte[] partIdByte = newPartId.encode();
        byte[] startTsByte = PrimitiveCodec.encodeLong(param.getStartTs());
        int len = txnIdByte.length + ByteUtils.StartTsLen + tableIdByte.length + partIdByte.length;
        // first appearance
        if (op == Op.NONE.getCode()) {
            // cache delete key
            byte[] dataKey = ByteUtils.encodePessimisticData(
                key,
                Op.PUTIFABSENT.getCode(),
                len,
                txnIdByte, startTsByte, tableIdByte, partIdByte);
            store.deletePrefix(dataKey);
            byte[] deleteKey = Arrays.copyOf(dataKey, dataKey.length);
            deleteKey[deleteKey.length - 2] = (byte) Op.DELETE.getCode();
            store.deletePrefix(deleteKey);
            deleteKey[deleteKey.length - 2] = (byte) Op.PUT.getCode();
            store.deletePrefix(deleteKey);
        } else {
            // cache delete key and set oldKeyValue
            byte[] dataKey = ByteUtils.encodePessimisticData(
                key,
                Op.PUTIFABSENT.getCode(),
                len,
                txnIdByte, startTsByte, tableIdByte, partIdByte);
            store.deletePrefix(dataKey);
            byte[] deleteKey = Arrays.copyOf(dataKey, dataKey.length);
            deleteKey[deleteKey.length - 2] = (byte) Op.DELETE.getCode();
            store.deletePrefix(deleteKey);
            deleteKey[deleteKey.length - 2] = (byte) Op.PUT.getCode();
            store.deletePrefix(deleteKey);

            byte[] newKey = Arrays.copyOf(dataKey, dataKey.length);
            newKey[newKey.length - 2] = (byte) op;
            store.put(new KeyValue(newKey, value));
        }
        param.addKey(key);
        CommonId partId = param.getPartId();
        if (partId == null) {
            partId = newPartId;
            param.setPartId(partId);
            param.setTableId(tableId);
        } else if (partId.equals(newPartId)) {
            param.addKey(key);
            if (param.getKeys().size() == TransactionUtil.max_pre_write_count) {
                boolean result = txnPessimisticRollBack(param, txnId, tableId, partId);
                if (!result) {
                    throw new RuntimeException(txnId + " " + partId + ",txnPessimisticRollBack false");
                }
                param.getKeys().clear();
                param.setPartId(null);
            }
        } else {
            boolean result = txnPessimisticRollBack(param, txnId, tableId, partId);
            if (!result) {
                throw new RuntimeException(txnId + " " + partId + ",txnPessimisticRollBack false");
            }
            param.getKeys().clear();
            param.addKey(key);
            param.setPartId(newPartId);
            param.setTableId(tableId);
        }
        return true;
    }

    private boolean txnPessimisticRollBack(PessimisticRollBackParam param, CommonId txnId, CommonId tableId, CommonId newPartId) {
        // 1、Async call sdk TxnPessimisticRollBack
        TxnPessimisticRollBack pessimisticRollBack = TxnPessimisticRollBack.builder()
            .isolationLevel(IsolationLevel.of(param.getIsolationLevel()))
            .startTs(param.getStartTs())
            .forUpdateTs(param.getForUpdateTs())
            .keys(param.getKeys())
            .build();
        try {
            StoreInstance store = Services.KV_STORE.getInstance(tableId, newPartId);
            return store.txnPessimisticLockRollback(pessimisticRollBack);
        } catch (RuntimeException e) {
            log.error(e.getMessage(), e);
            // 2、regin split
            Map<CommonId, List<byte[]>> partMap = TransactionUtil.multiKeySplitRegionId(tableId, txnId, param.getKeys());
            for (Map.Entry<CommonId, List<byte[]>> entry : partMap.entrySet()) {
                CommonId regionId = entry.getKey();
                List<byte[]> value = entry.getValue();
                StoreInstance store = Services.KV_STORE.getInstance(tableId, regionId);
                pessimisticRollBack.setKeys(value);
                boolean result = store.txnPessimisticLockRollback(pessimisticRollBack);
                if (!result) {
                    return false;
                }
            }
            return true;
        }
    }

    @Override
    public synchronized void fin(int pin, @Nullable Fin fin, Vertex vertex) {
        if (!(fin instanceof FinWithException)) {
            PessimisticRollBackParam param = vertex.getParam();
            if (param.getKeys().size() > 0) {
                CommonId txnId = vertex.getTask().getTxnId();
                boolean result = txnPessimisticRollBack(param, txnId, param.getTableId(), param.getPartId());
                if (!result) {
                    throw new RuntimeException(txnId + " " + param.getPartId() + ",txnPessimisticRollBack false");
                }
                param.getKeys().clear();
            }
            vertex.getSoleEdge().transformToNext(new Object[]{true});
        }
        vertex.getSoleEdge().fin(fin);
    }

}
