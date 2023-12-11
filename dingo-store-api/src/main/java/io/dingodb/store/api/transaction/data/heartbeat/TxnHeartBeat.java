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

package io.dingodb.store.api.transaction.data.heartbeat;

import io.dingodb.store.api.transaction.data.IsolationLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class TxnHeartBeat {
    private IsolationLevel isolationLevel;
    // The key of the lock to update.
    private byte[] primary_lock;
    // Start timestamp oracle of the large transaction.
    private long start_ts;
    // The new TTL the sender would like.
    // The advise_lock_ttl is the timestamp of milisecond.
    private long advise_lock_ttl;
}