/*
 * Copyright (c) 2008-2015, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.map.impl.recordstore;

import static com.hazelcast.map.impl.SizeEstimators.createMapSizeEstimator;

import com.hazelcast.concurrent.lock.LockService;
import com.hazelcast.concurrent.lock.LockStore;
import com.hazelcast.config.InMemoryFormat;
import com.hazelcast.internal.serialization.SerializationService;
import com.hazelcast.map.impl.MapContainer;
import com.hazelcast.map.impl.MapService;
import com.hazelcast.map.impl.MapServiceContext;
import com.hazelcast.map.impl.SizeEstimator;
import com.hazelcast.map.impl.mapstore.MapStoreContext;
import com.hazelcast.map.impl.record.Record;
import com.hazelcast.map.impl.record.RecordFactory;
import com.hazelcast.map.impl.record.Records;
import com.hazelcast.nio.serialization.Data;
import com.hazelcast.query.impl.Indexes;
import com.hazelcast.query.impl.QueryableEntry;
import com.hazelcast.spi.DefaultObjectNamespace;
import com.hazelcast.spi.NodeEngine;
import com.hazelcast.util.Clock;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Contains record store common parts.
 */
abstract class AbstractRecordStore implements RecordStore {

    // Concurrency level is 1 since at most one thread can write at a time.
    protected final ConcurrentMap<Data, Record> records = new ConcurrentHashMap<Data, Record>(1000, 0.75f, 1);

    protected final RecordFactory recordFactory;

    protected final String name;

    protected final MapContainer mapContainer;

    protected final MapServiceContext mapServiceContext;

    protected final SerializationService serializationService;

    protected final int partitionId;

    private SizeEstimator sizeEstimator;

    protected AbstractRecordStore(MapContainer mapContainer, int partitionId) {
        this.mapContainer = mapContainer;
        this.partitionId = partitionId;
        this.mapServiceContext = mapContainer.getMapServiceContext();
        this.serializationService = mapServiceContext.getNodeEngine().getSerializationService();
        this.name = mapContainer.getName();
        this.recordFactory = mapContainer.getRecordFactory();
        this.sizeEstimator = createMapSizeEstimator();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public MapContainer getMapContainer() {
        return mapContainer;
    }

    @Override
    public long getHeapCost() {
        return sizeEstimator.getSize();
    }

    protected long getNow() {
        return Clock.currentTimeMillis();
    }

    protected Record createRecord(Data key, Object value, long ttl, long now) {
        return mapContainer.createRecord(key, value, ttl, now);
    }

    protected Record createRecord(Data key, Object value, long now) {
        return mapContainer.createRecord(key, value, DEFAULT_TTL, now);
    }

    protected void accessRecord(Record record, long now) {
        record.setLastAccessTime(now);
        record.onAccess();
    }

    protected void accessRecord(Record record) {
        final long now = getNow();
        accessRecord(record, now);
    }

    protected void updateSizeEstimator(long recordSize) {
        sizeEstimator.add(recordSize);
    }

    protected long calculateRecordHeapCost(Record record) {
        return sizeEstimator.getCost(record);
    }

    /**
     * Returns total heap cost of collection.
     *
     * @param collection size to be calculated.
     * @return total size of collection.
     */
    protected long calculateRecordHeapCost(Collection<Record> collection) {
        long totalSize = 0L;
        for (Record record : collection) {
            totalSize += calculateRecordHeapCost(record);
        }
        return totalSize;
    }

    protected void resetSizeEstimator() {
        sizeEstimator.reset();
    }

    protected void updateRecord(Record record, Object value, long now) {
        accessRecord(record, now);
        record.setLastUpdateTime(now);
        record.onUpdate();
        recordFactory.setValue(record, value);
    }

    @Override
    public int getPartitionId() {
        return partitionId;
    }

    protected void saveIndex(Record record, Object oldValue) {
        Data dataKey = record.getKey();
        final Indexes indexes = mapContainer.getIndexes();
        if (indexes.hasIndex()) {
            Object value = Records.getValueOrCachedValue(record, serializationService);
            QueryableEntry queryableEntry = mapContainer.newQueryEntry(dataKey, value);
            indexes.saveEntryIndex(queryableEntry, oldValue);
        }
    }

    protected void removeIndex(Record record) {
        Indexes indexes = mapContainer.getIndexes();
        if (indexes.hasIndex()) {
            Data key = record.getKey();
            Object value = Records.getValueOrCachedValue(record, serializationService);
            indexes.removeEntryIndex(key, value);
        }
    }

    /**
     * Removes indexes by excluding keysToPreserve.
     *
     * @param keysToRemove   remove these keys from index.
     * @param keysToPreserve do not remove these keys.
     */

    protected void removeIndexByPreservingKeys(Collection<Record> keysToRemove, Set<Data> keysToPreserve) {
        Indexes indexes = mapContainer.getIndexes();
        if (indexes.hasIndex()) {
            for (Record record : keysToRemove) {
                Data key = record.getKey();
                if (!keysToPreserve.contains(key)) {
                    Object value = Records.getValueOrCachedValue(record, serializationService);
                    indexes.removeEntryIndex(key, value);
                }
            }
        }
    }

    protected LockStore createLockStore() {
        NodeEngine nodeEngine = mapServiceContext.getNodeEngine();
        final LockService lockService = nodeEngine.getSharedService(LockService.SERVICE_NAME);
        if (lockService == null) {
            return null;
        }
        return lockService.createLockStore(partitionId, new DefaultObjectNamespace(MapService.SERVICE_NAME, name));
    }

    protected RecordStoreLoader createRecordStoreLoader(MapStoreContext mapStoreContext) {
        return mapStoreContext.getMapStoreWrapper() == null
                ? RecordStoreLoader.EMPTY_LOADER : new BasicRecordStoreLoader(this);
    }

    protected void clearRecordsMap(Map<Data, Record> excludeRecords) {
        InMemoryFormat inMemoryFormat = recordFactory.getStorageFormat();
        switch (inMemoryFormat) {
            case BINARY:
            case OBJECT:
                records.clear();
                if (excludeRecords != null && !excludeRecords.isEmpty()) {
                    records.putAll(excludeRecords);
                }
                return;

            case NATIVE:
                Iterator<Record> iter = records.values().iterator();
                while (iter.hasNext()) {
                    Record record = iter.next();
                    if (excludeRecords == null || !excludeRecords.containsKey(record.getKey())) {
                        record.invalidate();
                        iter.remove();
                    }
                }
                return;

            default:
                throw new IllegalArgumentException("Unknown storage format: " + inMemoryFormat);
        }
    }

    protected Data toData(Object value) {
        return mapServiceContext.toData(value);
    }

    public void setSizeEstimator(SizeEstimator sizeEstimator) {
        this.sizeEstimator = sizeEstimator;
    }
}