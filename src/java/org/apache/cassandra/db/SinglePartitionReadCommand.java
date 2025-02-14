/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.db;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.*;

import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

import org.apache.cassandra.cache.IRowCacheEntry;
import org.apache.cassandra.cache.RowCacheKey;
import org.apache.cassandra.cache.RowCacheSentinel;
import org.apache.cassandra.concurrent.Stage;
import org.apache.cassandra.concurrent.StageManager;
import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.config.ColumnDefinition;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.lifecycle.*;
import org.apache.cassandra.db.filter.*;
import org.apache.cassandra.db.partitions.*;
import org.apache.cassandra.db.rows.*;
import org.apache.cassandra.exceptions.RequestExecutionException;
import org.apache.cassandra.io.sstable.format.SSTableReader;
import org.apache.cassandra.io.sstable.format.SSTableReadsListener;
import org.apache.cassandra.io.util.DataInputPlus;
import org.apache.cassandra.io.util.DataOutputPlus;
import org.apache.cassandra.metrics.TableMetrics;
import org.apache.cassandra.net.MessageOut;
import org.apache.cassandra.net.MessagingService;
import org.apache.cassandra.schema.IndexMetadata;
import org.apache.cassandra.service.CacheService;
import org.apache.cassandra.service.ClientState;
import org.apache.cassandra.service.StorageProxy;
import org.apache.cassandra.service.pager.*;
import org.apache.cassandra.thrift.ThriftResultsMerger;
import org.apache.cassandra.tracing.FacebookTracingImpl;
import org.apache.cassandra.tracing.Tracing;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.utils.SearchIterator;
import org.apache.cassandra.utils.btree.BTreeSet;
import org.apache.cassandra.utils.concurrent.OpOrder;
import org.apache.cassandra.utils.memory.HeapAllocator;

/**
 * A read command that selects a (part of a) single partition.
 */
public class SinglePartitionReadCommand extends ReadCommand
{
    protected static final SelectionDeserializer selectionDeserializer = new Deserializer();

    private final DecoratedKey partitionKey;
    private final ClusteringIndexFilter clusteringIndexFilter;

    private int oldestUnrepairedTombstone = Integer.MAX_VALUE;

    private SinglePartitionReadCommand(boolean isDigest,
                                       int digestVersion,
                                       boolean isForThrift,
                                       CFMetaData metadata,
                                       int nowInSec,
                                       ColumnFilter columnFilter,
                                       RowFilter rowFilter,
                                       DataLimits limits,
                                       DecoratedKey partitionKey,
                                       ClusteringIndexFilter clusteringIndexFilter,
                                       IndexMetadata index)
    {
        super(Kind.SINGLE_PARTITION, isDigest, digestVersion, isForThrift, metadata, nowInSec, columnFilter, rowFilter, limits, index);
        assert partitionKey.getPartitioner() == metadata.partitioner;
        this.partitionKey = partitionKey;
        this.clusteringIndexFilter = clusteringIndexFilter;
    }

    /**
     * Creates a new read command on a single partition.
     *
     * @param isForThrift whether the query is for thrift or not.
     * @param metadata the table to query.
     * @param nowInSec the time in seconds to use are "now" for this query.
     * @param columnFilter the column filter to use for the query.
     * @param rowFilter the row filter to use for the query.
     * @param limits the limits to use for the query.
     * @param partitionKey the partition key for the partition to query.
     * @param clusteringIndexFilter the clustering index filter to use for the query.
     * @param indexMetadata explicitly specified index to use for the query
     *
     * @return a newly created read command.
     */
    public static SinglePartitionReadCommand create(boolean isForThrift,
                                                    CFMetaData metadata,
                                                    int nowInSec,
                                                    ColumnFilter columnFilter,
                                                    RowFilter rowFilter,
                                                    DataLimits limits,
                                                    DecoratedKey partitionKey,
                                                    ClusteringIndexFilter clusteringIndexFilter,
                                                    IndexMetadata indexMetadata)
    {
        return new SinglePartitionReadCommand(false,
                                              0,
                                              isForThrift,
                                              metadata,
                                              nowInSec,
                                              columnFilter,
                                              rowFilter,
                                              limits,
                                              partitionKey,
                                              clusteringIndexFilter,
                                              indexMetadata);
    }

    /**
     * Creates a new read command on a single partition.
     *
     * @param metadata the table to query.
     * @param nowInSec the time in seconds to use are "now" for this query.
     * @param columnFilter the column filter to use for the query.
     * @param rowFilter the row filter to use for the query.
     * @param limits the limits to use for the query.
     * @param partitionKey the partition key for the partition to query.
     * @param clusteringIndexFilter the clustering index filter to use for the query.
     *
     * @return a newly created read command.
     */
    public static SinglePartitionReadCommand create(CFMetaData metadata,
                                                    int nowInSec,
                                                    ColumnFilter columnFilter,
                                                    RowFilter rowFilter,
                                                    DataLimits limits,
                                                    DecoratedKey partitionKey,
                                                    ClusteringIndexFilter clusteringIndexFilter)
    {
        return create(false, metadata, nowInSec, columnFilter, rowFilter, limits, partitionKey, clusteringIndexFilter);
    }

    /**
     * Creates a new read command on a single partition.
     *
     * @param isForThrift whether the query is for thrift or not.
     * @param metadata the table to query.
     * @param nowInSec the time in seconds to use are "now" for this query.
     * @param columnFilter the column filter to use for the query.
     * @param rowFilter the row filter to use for the query.
     * @param limits the limits to use for the query.
     * @param partitionKey the partition key for the partition to query.
     * @param clusteringIndexFilter the clustering index filter to use for the query.
     *
     * @return a newly created read command.
     */
    public static SinglePartitionReadCommand create(boolean isForThrift,
                                                    CFMetaData metadata,
                                                    int nowInSec,
                                                    ColumnFilter columnFilter,
                                                    RowFilter rowFilter,
                                                    DataLimits limits,
                                                    DecoratedKey partitionKey,
                                                    ClusteringIndexFilter clusteringIndexFilter)
    {
        return create(isForThrift,
                      metadata,
                      nowInSec,
                      columnFilter,
                      rowFilter,
                      limits,
                      partitionKey,
                      clusteringIndexFilter,
                      findIndex(metadata, rowFilter));
    }

    /**
     * Creates a new read command on a single partition.
     *
     * @param metadata the table to query.
     * @param nowInSec the time in seconds to use are "now" for this query.
     * @param key the partition key for the partition to query.
     * @param columnFilter the column filter to use for the query.
     * @param filter the clustering index filter to use for the query.
     *
     * @return a newly created read command. The returned command will use no row filter and have no limits.
     */
    public static SinglePartitionReadCommand create(CFMetaData metadata,
                                                    int nowInSec,
                                                    DecoratedKey key,
                                                    ColumnFilter columnFilter,
                                                    ClusteringIndexFilter filter)
    {
        return create(metadata, nowInSec, columnFilter, RowFilter.NONE, DataLimits.NONE, key, filter);
    }

    /**
     * Creates a new read command that queries a single partition in its entirety.
     *
     * @param metadata the table to query.
     * @param nowInSec the time in seconds to use are "now" for this query.
     * @param key the partition key for the partition to query.
     *
     * @return a newly created read command that queries all the rows of {@code key}.
     */
    public static SinglePartitionReadCommand fullPartitionRead(CFMetaData metadata, int nowInSec, DecoratedKey key)
    {
        return create(metadata, nowInSec, key, Slices.ALL);
    }

    /**
     * Creates a new read command that queries a single partition in its entirety.
     *
     * @param metadata the table to query.
     * @param nowInSec the time in seconds to use are "now" for this query.
     * @param key the partition key for the partition to query.
     *
     * @return a newly created read command that queries all the rows of {@code key}.
     */
    public static SinglePartitionReadCommand fullPartitionRead(CFMetaData metadata, int nowInSec, ByteBuffer key)
    {
        return create(metadata, nowInSec, metadata.decorateKey(key), Slices.ALL);
    }

    /**
     * Creates a new single partition slice command for the provided single slice.
     *
     * @param metadata the table to query.
     * @param nowInSec the time in seconds to use are "now" for this query.
     * @param key the partition key for the partition to query.
     * @param slice the slice of rows to query.
     *
     * @return a newly created read command that queries {@code slice} in {@code key}. The returned query will
     * query every columns for the table (without limit or row filtering) and be in forward order.
     */
    public static SinglePartitionReadCommand create(CFMetaData metadata, int nowInSec, DecoratedKey key, Slice slice)
    {
        return create(metadata, nowInSec, key, Slices.with(metadata.comparator, slice));
    }

    /**
     * Creates a new single partition slice command for the provided slices.
     *
     * @param metadata the table to query.
     * @param nowInSec the time in seconds to use are "now" for this query.
     * @param key the partition key for the partition to query.
     * @param slices the slices of rows to query.
     *
     * @return a newly created read command that queries the {@code slices} in {@code key}. The returned query will
     * query every columns for the table (without limit or row filtering) and be in forward order.
     */
    public static SinglePartitionReadCommand create(CFMetaData metadata, int nowInSec, DecoratedKey key, Slices slices)
    {
        ClusteringIndexSliceFilter filter = new ClusteringIndexSliceFilter(slices, false);
        return create(metadata, nowInSec, ColumnFilter.all(metadata), RowFilter.NONE, DataLimits.NONE, key, filter);
    }

    /**
     * Creates a new single partition slice command for the provided slices.
     *
     * @param metadata the table to query.
     * @param nowInSec the time in seconds to use are "now" for this query.
     * @param key the partition key for the partition to query.
     * @param slices the slices of rows to query.
     *
     * @return a newly created read command that queries the {@code slices} in {@code key}. The returned query will
     * query every columns for the table (without limit or row filtering) and be in forward order.
     */
    public static SinglePartitionReadCommand create(CFMetaData metadata, int nowInSec, ByteBuffer key, Slices slices)
    {
        return create(metadata, nowInSec, metadata.decorateKey(key), slices);
    }

    /**
     * Creates a new single partition name command for the provided rows.
     *
     * @param metadata the table to query.
     * @param nowInSec the time in seconds to use are "now" for this query.
     * @param key the partition key for the partition to query.
     * @param names the clustering for the rows to query.
     *
     * @return a newly created read command that queries the {@code names} in {@code key}. The returned query will
     * query every columns (without limit or row filtering) and be in forward order.
     */
    public static SinglePartitionReadCommand create(CFMetaData metadata, int nowInSec, DecoratedKey key, NavigableSet<Clustering> names)
    {
        ClusteringIndexNamesFilter filter = new ClusteringIndexNamesFilter(names, false);
        return create(metadata, nowInSec, ColumnFilter.all(metadata), RowFilter.NONE, DataLimits.NONE, key, filter);
    }

    /**
     * Creates a new single partition name command for the provided row.
     *
     * @param metadata the table to query.
     * @param nowInSec the time in seconds to use are "now" for this query.
     * @param key the partition key for the partition to query.
     * @param name the clustering for the row to query.
     *
     * @return a newly created read command that queries {@code name} in {@code key}. The returned query will
     * query every columns (without limit or row filtering).
     */
    public static SinglePartitionReadCommand create(CFMetaData metadata, int nowInSec, DecoratedKey key, Clustering name)
    {
        return create(metadata, nowInSec, key, FBUtilities.singleton(name, metadata.comparator));
    }

    public SinglePartitionReadCommand copy()
    {
        return new SinglePartitionReadCommand(isDigestQuery(),
                                              digestVersion(),
                                              isForThrift(),
                                              metadata(),
                                              nowInSec(),
                                              columnFilter(),
                                              rowFilter(),
                                              limits(),
                                              partitionKey(),
                                              clusteringIndexFilter(),
                                              indexMetadata());
    }

    public SinglePartitionReadCommand copyAsDigestQuery()
    {
        return new SinglePartitionReadCommand(true,
                                              digestVersion(),
                                              isForThrift(),
                                              metadata(),
                                              nowInSec(),
                                              columnFilter(),
                                              rowFilter(),
                                              limits(),
                                              partitionKey(),
                                              clusteringIndexFilter(),
                                              indexMetadata());
    }

    public SinglePartitionReadCommand withUpdatedClusteringIndexFilter(ClusteringIndexFilter filter)
    {
        return new SinglePartitionReadCommand(isDigestQuery(),
                                              digestVersion(),
                                              isForThrift(),
                                              metadata(),
                                              nowInSec(),
                                              columnFilter(),
                                              rowFilter(),
                                              limits(),
                                              partitionKey(),
                                              filter,
                                              indexMetadata());
    }

    static SinglePartitionReadCommand legacySliceCommand(boolean isDigest,
                                                         int digestVersion,
                                                         CFMetaData metadata,
                                                         int nowInSec,
                                                         ColumnFilter columnFilter,
                                                         DataLimits limits,
                                                         DecoratedKey partitionKey,
                                                         ClusteringIndexSliceFilter filter)
    {
        // messages from old nodes will expect the thrift format, so always use 'true' for isForThrift
        return new SinglePartitionReadCommand(isDigest,
                                              digestVersion,
                                              true,
                                              metadata,
                                              nowInSec,
                                              columnFilter,
                                              RowFilter.NONE,
                                              limits,
                                              partitionKey,
                                              filter,
                                              null);
    }

    static SinglePartitionReadCommand legacyNamesCommand(boolean isDigest,
                                                         int digestVersion,
                                                         CFMetaData metadata,
                                                         int nowInSec,
                                                         ColumnFilter columnFilter,
                                                         DecoratedKey partitionKey,
                                                         ClusteringIndexNamesFilter filter)
    {
        // messages from old nodes will expect the thrift format, so always use 'true' for isForThrift
        return new SinglePartitionReadCommand(isDigest, digestVersion, true, metadata, nowInSec, columnFilter, RowFilter.NONE, DataLimits.NONE, partitionKey, filter,null);
    }

    public DecoratedKey partitionKey()
    {
        return partitionKey;
    }

    public ClusteringIndexFilter clusteringIndexFilter()
    {
        return clusteringIndexFilter;
    }

    public ClusteringIndexFilter clusteringIndexFilter(DecoratedKey key)
    {
        return clusteringIndexFilter;
    }

    public long getTimeout()
    {
        return DatabaseDescriptor.getReadRpcTimeout();
    }

    public boolean selectsKey(DecoratedKey key)
    {
        if (!this.partitionKey().equals(key))
            return false;

        return rowFilter().partitionKeyRestrictionsAreSatisfiedBy(key, metadata().getKeyValidator());
    }

    public boolean selectsClustering(DecoratedKey key, Clustering clustering)
    {
        if (clustering == Clustering.STATIC_CLUSTERING)
            return !columnFilter().fetchedColumns().statics.isEmpty();

        if (!clusteringIndexFilter().selects(clustering))
            return false;

        return rowFilter().clusteringKeyRestrictionsAreSatisfiedBy(clustering);
    }

    /**
     * Returns a new command suitable to paging from the last returned row.
     *
     * @param lastReturned the last row returned by the previous page. The newly created command
     * will only query row that comes after this (in query order). This can be {@code null} if this
     * is the first page.
     * @param pageSize the size to use for the page to query.
     *
     * @return the newly create command.
     */
    public SinglePartitionReadCommand forPaging(Clustering lastReturned, int pageSize)
    {
        // We shouldn't have set digest yet when reaching that point
        assert !isDigestQuery();
        return create(isForThrift(),
                      metadata(),
                      nowInSec(),
                      columnFilter(),
                      rowFilter(),
                      limits().forPaging(pageSize),
                      partitionKey(),
                      lastReturned == null ? clusteringIndexFilter() : clusteringIndexFilter.forPaging(metadata().comparator, lastReturned, false));
    }

    public PartitionIterator execute(ConsistencyLevel consistency, ClientState clientState) throws RequestExecutionException
    {
        return StorageProxy.read(Group.one(this), consistency, clientState);
    }

    public SinglePartitionPager getPager(PagingState pagingState, int protocolVersion)
    {
        return getPager(this, pagingState, protocolVersion);
    }

    private static SinglePartitionPager getPager(SinglePartitionReadCommand command, PagingState pagingState, int protocolVersion)
    {
        return new SinglePartitionPager(command, pagingState, protocolVersion);
    }

    protected void recordLatency(TableMetrics metric, long latencyNanos)
    {
        metric.readLatency.addNano(latencyNanos);
    }

    @SuppressWarnings("resource") // we close the created iterator through closing the result of this method (and SingletonUnfilteredPartitionIterator ctor cannot fail)
    protected UnfilteredPartitionIterator queryStorage(final ColumnFamilyStore cfs, ReadOrderGroup orderGroup)
    {
        UnfilteredRowIterator partition = cfs.isRowCacheEnabled()
                                        ? getThroughCache(cfs, orderGroup.baseReadOpOrderGroup())
                                        : queryMemtableAndDisk(cfs, orderGroup.baseReadOpOrderGroup());
        return new SingletonUnfilteredPartitionIterator(partition, isForThrift());
    }

    /**
     * Fetch the rows requested if in cache; if not, read it from disk and cache it.
     * <p>
     * If the partition is cached, and the filter given is within its bounds, we return
     * from cache, otherwise from disk.
     * <p>
     * If the partition is is not cached, we figure out what filter is "biggest", read
     * that from disk, then filter the result and either cache that or return it.
     */
    private UnfilteredRowIterator getThroughCache(ColumnFamilyStore cfs, OpOrder.Group readOp)
    {
        assert !cfs.isIndex(); // CASSANDRA-5732
        assert cfs.isRowCacheEnabled() : String.format("Row cache is not enabled on table [%s]", cfs.name);

        RowCacheKey key = new RowCacheKey(metadata().ksAndCFName, partitionKey());

        // Attempt a sentinel-read-cache sequence.  if a write invalidates our sentinel, we'll return our
        // (now potentially obsolete) data, but won't cache it. see CASSANDRA-3862
        // TODO: don't evict entire partitions on writes (#2864)
        IRowCacheEntry cached = CacheService.instance.rowCache.get(key);
        if (cached != null)
        {
            if (cached instanceof RowCacheSentinel)
            {
                // Some other read is trying to cache the value, just do a normal non-caching read
                Tracing.trace("Row cache miss (race)");
                cfs.metric.rowCacheMiss.inc();
                return queryMemtableAndDisk(cfs, readOp);
            }

            CachedPartition cachedPartition = (CachedPartition)cached;
            if (cfs.isFilterFullyCoveredBy(clusteringIndexFilter(), limits(), cachedPartition, nowInSec()))
            {
                cfs.metric.rowCacheHit.inc();
                Tracing.trace("Row cache hit");
                UnfilteredRowIterator unfilteredRowIterator = clusteringIndexFilter().getUnfilteredRowIterator(columnFilter(), cachedPartition);
                cfs.metric.updateSSTableIterated(0);
                return unfilteredRowIterator;
            }

            cfs.metric.rowCacheHitOutOfRange.inc();
            Tracing.trace("Ignoring row cache as cached value could not satisfy query");
            return queryMemtableAndDisk(cfs, readOp);
        }

        cfs.metric.rowCacheMiss.inc();
        Tracing.trace("Row cache miss");

        // Note that on tables with no clustering keys, any positive value of
        // rowsToCache implies caching the full partition
        boolean cacheFullPartitions = metadata().clusteringColumns().size() > 0 ?
                                      metadata().params.caching.cacheAllRows() :
                                      metadata().params.caching.cacheRows();

        // To be able to cache what we read, what we read must at least covers what the cache holds, that
        // is the 'rowsToCache' first rows of the partition. We could read those 'rowsToCache' first rows
        // systematically, but we'd have to "extend" that to whatever is needed for the user query that the
        // 'rowsToCache' first rows don't cover and it's not trivial with our existing filters. So currently
        // we settle for caching what we read only if the user query does query the head of the partition since
        // that's the common case of when we'll be able to use the cache anyway. One exception is if we cache
        // full partitions, in which case we just always read it all and cache.
        if (cacheFullPartitions || clusteringIndexFilter().isHeadFilter())
        {
            RowCacheSentinel sentinel = new RowCacheSentinel();
            boolean sentinelSuccess = CacheService.instance.rowCache.putIfAbsent(key, sentinel);
            boolean sentinelReplaced = false;

            try
            {
                final int rowsToCache = metadata().params.caching.rowsPerPartitionToCache();
                final boolean enforceStrictLiveness = metadata().enforceStrictLiveness();

                @SuppressWarnings("resource") // we close on exception or upon closing the result of this method
                UnfilteredRowIterator iter = fullPartitionRead(metadata(), nowInSec(), partitionKey()).queryMemtableAndDisk(cfs, readOp);
                try
                {
                    // Use a custom iterator instead of DataLimits to avoid stopping the original iterator
                    UnfilteredRowIterator toCacheIterator = new WrappingUnfilteredRowIterator(iter)
                    {
                        private int rowsCounted = 0;

                        @Override
                        public boolean hasNext()
                        {
                            return rowsCounted < rowsToCache && super.hasNext();
                        }

                        @Override
                        public Unfiltered next()
                        {
                            Unfiltered unfiltered = super.next();
                            if (unfiltered.isRow())
                            {
                                Row row = (Row) unfiltered;
                                if (row.hasLiveData(nowInSec(), enforceStrictLiveness))
                                    rowsCounted++;
                            }
                            return unfiltered;
                        }
                    };

                    // We want to cache only rowsToCache rows
                    CachedPartition toCache = CachedBTreePartition.create(toCacheIterator, nowInSec());
                    if (sentinelSuccess && !toCache.isEmpty())
                    {
                        Tracing.trace("Caching {} rows", toCache.rowCount());
                        CacheService.instance.rowCache.replace(key, sentinel, toCache);
                        // Whether or not the previous replace has worked, our sentinel is not in the cache anymore
                        sentinelReplaced = true;
                    }

                    // We then re-filter out what this query wants.
                    // Note that in the case where we don't cache full partitions, it's possible that the current query is interested in more
                    // than what we've cached, so we can't just use toCache.
                    UnfilteredRowIterator cacheIterator = clusteringIndexFilter().getUnfilteredRowIterator(columnFilter(), toCache);
                    if (cacheFullPartitions)
                    {
                        // Everything is guaranteed to be in 'toCache', we're done with 'iter'
                        assert !iter.hasNext();
                        iter.close();
                        return cacheIterator;
                    }
                    return UnfilteredRowIterators.concat(cacheIterator, clusteringIndexFilter().filterNotIndexed(columnFilter(), iter));
                }
                catch (RuntimeException | Error e)
                {
                    iter.close();
                    throw e;
                }
            }
            finally
            {
                if (sentinelSuccess && !sentinelReplaced)
                    cfs.invalidateCachedPartition(key);
            }
        }

        Tracing.trace("Fetching data but not populating cache as query does not query from the start of the partition");
        return queryMemtableAndDisk(cfs, readOp);
    }

    /**
     * Queries both memtable and sstables to fetch the result of this query.
     * <p>
     * Please note that this method:
     *   1) does not check the row cache.
     *   2) does not apply the query limit, nor the row filter (and so ignore 2ndary indexes).
     *      Those are applied in {@link ReadCommand#executeLocally}.
     *   3) does not record some of the read metrics (latency, scanned cells histograms) nor
     *      throws TombstoneOverwhelmingException.
     * It is publicly exposed because there is a few places where that is exactly what we want,
     * but it should be used only where you know you don't need thoses things.
     * <p>
     * Also note that one must have "started" a {@code OpOrder.Group} on the queried table, and that is
     * to enforce that that it is required as parameter, even though it's not explicitlly used by the method.
     */
    public UnfilteredRowIterator queryMemtableAndDisk(ColumnFamilyStore cfs, OpOrder.Group readOp)
    {
        Tracing.trace("Executing single-partition query on {}", cfs.name);

        if (cfs.engine != null)
        {
            cfs.metric.samplers.get(TableMetrics.Sampler.READS).addSample(partitionKey().getKey(), partitionKey().hashCode(), 1);
            return cfs.engine.queryStorage(cfs, this);
        }

        return queryMemtableAndDiskInternal(cfs);
    }

    @Override
    protected int oldestUnrepairedTombstone()
    {
        return oldestUnrepairedTombstone;
    }

    private UnfilteredRowIterator queryMemtableAndDiskInternal(ColumnFamilyStore cfs)
    {
        /*
         * We have 2 main strategies:
         *   1) We query memtables and sstables simulateneously. This is our most generic strategy and the one we use
         *      unless we have a names filter that we know we can optimize futher.
         *   2) If we have a name filter (so we query specific rows), we can make a bet: that all column for all queried row
         *      will have data in the most recent sstable(s), thus saving us from reading older ones. This does imply we
         *      have a way to guarantee we have all the data for what is queried, which is only possible for name queries
         *      and if we have neither non-frozen collections/UDTs nor counters (indeed, for a non-frozen collection or UDT,
         *      we can't guarantee an older sstable won't have some elements that weren't in the most recent sstables,
         *      and counters are intrinsically a collection of shards and so have the same problem).
         */
        if (clusteringIndexFilter() instanceof ClusteringIndexNamesFilter && !queriesMulticellType())
            return queryMemtableAndSSTablesInTimestampOrder(cfs, (ClusteringIndexNamesFilter)clusteringIndexFilter());

        Tracing.trace("Acquiring sstable references");
        ColumnFamilyStore.ViewFragment view = cfs.select(View.select(SSTableSet.LIVE, partitionKey()));

        List<UnfilteredRowIterator> iterators = new ArrayList<>(Iterables.size(view.memtables) + view.sstables.size());
        ClusteringIndexFilter filter = clusteringIndexFilter();

        try
        {
            for (Memtable memtable : view.memtables)
            {
                Partition partition = memtable.getPartition(partitionKey());
                if (partition == null)
                    continue;

                @SuppressWarnings("resource") // 'iter' is added to iterators which is closed on exception, or through the closing of the final merged iterator
                UnfilteredRowIterator iter = filter.getUnfilteredRowIterator(columnFilter(), partition);
                oldestUnrepairedTombstone = Math.min(oldestUnrepairedTombstone, partition.stats().minLocalDeletionTime);
                iterators.add(isForThrift() ? ThriftResultsMerger.maybeWrap(iter, nowInSec()) : iter);
            }
            /*
             * We can't eliminate full sstables based on the timestamp of what we've already read like
             * in collectTimeOrderedData, but we still want to eliminate sstable whose maxTimestamp < mostRecentTombstone
             * we've read. We still rely on the sstable ordering by maxTimestamp since if
             *   maxTimestamp_s1 > maxTimestamp_s0,
             * we're guaranteed that s1 cannot have a row tombstone such that
             *   timestamp(tombstone) > maxTimestamp_s0
             * since we necessarily have
             *   timestamp(tombstone) <= maxTimestamp_s1
             * In other words, iterating in maxTimestamp order allow to do our mostRecentPartitionTombstone elimination
             * in one pass, and minimize the number of sstables for which we read a partition tombstone.
             */
            Collections.sort(view.sstables, SSTableReader.maxTimestampComparator);
            List<SSTableReader> skippedSSTables = null;
            long mostRecentPartitionTombstone = Long.MIN_VALUE;
            long minTimestamp = Long.MAX_VALUE;
            int nonIntersectingSSTables = 0;
            SSTableReadMetricsCollector metricsCollector = new SSTableReadMetricsCollector();

            for (SSTableReader sstable : view.sstables)
            {
                minTimestamp = Math.min(minTimestamp, sstable.getMinTimestamp());
                // if we've already seen a partition tombstone with a timestamp greater
                // than the most recent update to this sstable, we can skip it
                if (sstable.getMaxTimestamp() < mostRecentPartitionTombstone)
                    break;

                if (!shouldInclude(sstable))
                {
                    nonIntersectingSSTables++;
                    // sstable contains no tombstone if maxLocalDeletionTime == Integer.MAX_VALUE, so we can safely skip those entirely
                    if (sstable.hasTombstones())
                    {
                        if (skippedSSTables == null)
                            skippedSSTables = new ArrayList<>();
                        skippedSSTables.add(sstable);
                    }
                    continue;
                }

                @SuppressWarnings("resource") // 'iter' is added to iterators which is closed on exception, or through the closing of the final merged iterator
                UnfilteredRowIterator iter = filter.filter(sstable.iterator(partitionKey(),
                                                                            columnFilter(),
                                                                            filter.isReversed(),
                                                                            isForThrift(),
                                                                            metricsCollector));
                if (!sstable.isRepaired())
                    oldestUnrepairedTombstone = Math.min(oldestUnrepairedTombstone, sstable.getMinLocalDeletionTime());

                iterators.add(isForThrift() ? ThriftResultsMerger.maybeWrap(iter, nowInSec()) : iter);
                mostRecentPartitionTombstone = Math.max(mostRecentPartitionTombstone, iter.partitionLevelDeletion().markedForDeleteAt());
            }

            int includedDueToTombstones = 0;
            // Check for partition tombstones in the skipped sstables
            if (skippedSSTables != null)
            {
                for (SSTableReader sstable : skippedSSTables)
                {
                    if (sstable.getMaxTimestamp() <= minTimestamp)
                        continue;

                    @SuppressWarnings("resource") // 'iter' is either closed right away, or added to iterators which is close on exception, or through the closing of the final merged iterator
                    UnfilteredRowIterator iter = filter.filter(sstable.iterator(partitionKey(),
                                                                                columnFilter(),
                                                                                filter.isReversed(),
                                                                                isForThrift(),
                                                                                metricsCollector));

                    if (iter.partitionLevelDeletion().markedForDeleteAt() > minTimestamp)
                    {
                        iterators.add(iter);
                        if (!sstable.isRepaired())
                            oldestUnrepairedTombstone = Math.min(oldestUnrepairedTombstone, sstable.getMinLocalDeletionTime());
                        includedDueToTombstones++;
                    }
                    else
                    {
                        iter.close();
                    }
                }
            }
            if (Tracing.isTracing())
                Tracing.trace("Skipped {}/{} non-slice-intersecting sstables, included {} due to tombstones",
                              nonIntersectingSSTables, view.sstables.size(), includedDueToTombstones);

            cfs.metric.updateSSTableIterated(metricsCollector.getMergedSSTables());

            if (iterators.isEmpty())
                return EmptyIterators.unfilteredRow(cfs.metadata, partitionKey(), filter.isReversed());

            Tracing.trace("Merging data from memtables and {} sstables", metricsCollector.getMergedSSTables());

            @SuppressWarnings("resource") //  Closed through the closing of the result of that method.
            UnfilteredRowIterator merged = UnfilteredRowIterators.merge(iterators, nowInSec());
            if (!merged.isEmpty())
            {
                DecoratedKey key = merged.partitionKey();
                cfs.metric.samplers.get(TableMetrics.Sampler.READS).addSample(key.getKey(), key.hashCode(), 1);
            }

            return merged;
        }
        catch (RuntimeException | Error e)
        {
            try
            {
                FBUtilities.closeAll(iterators);
            }
            catch (Exception suppressed)
            {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    private boolean shouldInclude(SSTableReader sstable)
    {
        // If some static columns are queried, we should always include the sstable: the clustering values stats of the sstable
        // don't tell us if the sstable contains static values in particular.
        // TODO: we could record if a sstable contains any static value at all.
        if (!columnFilter().fetchedColumns().statics.isEmpty())
            return true;

        return clusteringIndexFilter().shouldInclude(sstable);
    }

    private boolean queriesMulticellType()
    {
        for (ColumnDefinition column : columnFilter().fetchedColumns())
        {
            if (column.type.isMultiCell() || column.type.isCounter())
                return true;
        }
        return false;
    }

    /**
     * Do a read by querying the memtable(s) first, and then each relevant sstables sequentially by order of the sstable
     * max timestamp.
     *
     * This is used for names query in the hope of only having to query the 1 or 2 most recent query and then knowing nothing
     * more recent could be in the older sstables (which we can only guarantee if we know exactly which row we queries, and if
     * no collection or counters are included).
     * This method assumes the filter is a {@code ClusteringIndexNamesFilter}.
     */
    private UnfilteredRowIterator queryMemtableAndSSTablesInTimestampOrder(ColumnFamilyStore cfs, ClusteringIndexNamesFilter filter)
    {
        Tracing.trace("Acquiring sstable references");
        ColumnFamilyStore.ViewFragment view = cfs.select(View.select(SSTableSet.LIVE, partitionKey()));

        ImmutableBTreePartition result = null;

        Tracing.trace("Merging memtable contents");
        for (Memtable memtable : view.memtables)
        {
            Partition partition = memtable.getPartition(partitionKey());
            if (partition == null)
                continue;

            try (UnfilteredRowIterator iter = filter.getUnfilteredRowIterator(columnFilter(), partition))
            {
                if (iter.isEmpty())
                    continue;

                result = add(isForThrift() ? ThriftResultsMerger.maybeWrap(iter, nowInSec()) : iter, result, filter, false);
            }
        }

        /* add the SSTables on disk */
        Collections.sort(view.sstables, SSTableReader.maxTimestampComparator);
        boolean onlyUnrepaired = true;
        // read sorted sstables
        SSTableReadMetricsCollector metricsCollector = new SSTableReadMetricsCollector();
        for (SSTableReader sstable : view.sstables)
        {
            // if we've already seen a partition tombstone with a timestamp greater
            // than the most recent update to this sstable, we're done, since the rest of the sstables
            // will also be older
            if (result != null && sstable.getMaxTimestamp() < result.partitionLevelDeletion().markedForDeleteAt())
                break;

            long currentMaxTs = sstable.getMaxTimestamp();
            filter = reduceFilter(filter, result, currentMaxTs);
            if (filter == null)
                break;

            if (!shouldInclude(sstable))
            {
                // This mean that nothing queried by the filter can be in the sstable. One exception is the top-level partition deletion
                // however: if it is set, it impacts everything and must be included. Getting that top-level partition deletion costs us
                // some seek in general however (unless the partition is indexed and is in the key cache), so we first check if the sstable
                // has any tombstone at all as a shortcut.
                if (!sstable.hasTombstones())
                    continue; // Means no tombstone at all, we can skip that sstable

                // We need to get the partition deletion and include it if it's live. In any case though, we're done with that sstable.
                try (UnfilteredRowIterator iter = filter.filter(sstable.iterator(partitionKey(),
                                                                                 columnFilter(),
                                                                                 filter.isReversed(),
                                                                                 isForThrift(),
                                                                                 metricsCollector)))
                {
                    if (!iter.partitionLevelDeletion().isLive())
                        result = add(UnfilteredRowIterators.noRowsIterator(iter.metadata(), iter.partitionKey(), Rows.EMPTY_STATIC_ROW, iter.partitionLevelDeletion(), filter.isReversed()), result, filter, sstable.isRepaired());
                    else
                        result = add(iter, result, filter, sstable.isRepaired());
                }
                continue;
            }

            Tracing.trace("Merging data from sstable {}", sstable.descriptor.generation);
            try (UnfilteredRowIterator iter = filter.filter(sstable.iterator(partitionKey(),
                                                                             columnFilter(),
                                                                             filter.isReversed(),
                                                                             isForThrift(),
                                                                             metricsCollector)))
            {
                if (iter.isEmpty())
                    continue;

                if (sstable.isRepaired())
                    onlyUnrepaired = false;
                result = add(isForThrift() ? ThriftResultsMerger.maybeWrap(iter, nowInSec()) : iter, result, filter, sstable.isRepaired());
            }
        }

        cfs.metric.updateSSTableIterated(metricsCollector.getMergedSSTables());

        if (result == null || result.isEmpty())
            return EmptyIterators.unfilteredRow(metadata(), partitionKey(), false);

        DecoratedKey key = result.partitionKey();
        cfs.metric.samplers.get(TableMetrics.Sampler.READS).addSample(key.getKey(), key.hashCode(), 1);

        // "hoist up" the requested data into a more recent sstable
        if (metricsCollector.getMergedSSTables() > cfs.getMinimumCompactionThreshold()
            && onlyUnrepaired
            && !cfs.isAutoCompactionDisabled()
            && cfs.getCompactionStrategyManager().shouldDefragment())
        {
            // !!WARNING!!   if we stop copying our data to a heap-managed object,
            //               we will need to track the lifetime of this mutation as well
            Tracing.trace("Defragmenting requested data");

            try (UnfilteredRowIterator iter = result.unfilteredIterator(columnFilter(), Slices.ALL, false))
            {
                final Mutation mutation = new Mutation(PartitionUpdate.fromIterator(iter));
                StageManager.getStage(Stage.MUTATION).execute(new Runnable()
                {
                    public void run()
                    {
                        // skipping commitlog and index updates is fine since we're just de-fragmenting existing data
                        Keyspace.open(mutation.getKeyspaceName()).apply(mutation, false, false);
                    }
                });
            }
        }

        return result.unfilteredIterator(columnFilter(), Slices.ALL, clusteringIndexFilter().isReversed());
    }

    private ImmutableBTreePartition add(UnfilteredRowIterator iter, ImmutableBTreePartition result, ClusteringIndexNamesFilter filter, boolean isRepaired)
    {
        if (!isRepaired)
            oldestUnrepairedTombstone = Math.min(oldestUnrepairedTombstone, iter.stats().minLocalDeletionTime);

        int maxRows = Math.max(filter.requestedRows().size(), 1);
        if (result == null)
            return ImmutableBTreePartition.create(iter, maxRows);

        try (UnfilteredRowIterator merged = UnfilteredRowIterators.merge(Arrays.asList(iter, result.unfilteredIterator(columnFilter(), Slices.ALL, filter.isReversed())), nowInSec()))
        {
            return ImmutableBTreePartition.create(merged, maxRows);
        }
    }

    private ClusteringIndexNamesFilter reduceFilter(ClusteringIndexNamesFilter filter, Partition result, long sstableTimestamp)
    {
        if (result == null)
            return filter;

        SearchIterator<Clustering, Row> searchIter = result.searchIterator(columnFilter(), false);

        PartitionColumns columns = columnFilter().fetchedColumns();
        NavigableSet<Clustering> clusterings = filter.requestedRows();

        // We want to remove rows for which we have values for all requested columns. We have to deal with both static and regular rows.
        // TODO: we could also remove a selected column if we've found values for every requested row but we'll leave
        // that for later.

        boolean removeStatic = false;
        if (!columns.statics.isEmpty())
        {
            Row staticRow = searchIter.next(Clustering.STATIC_CLUSTERING);
            removeStatic = staticRow != null && canRemoveRow(staticRow, columns.statics, sstableTimestamp);
        }

        NavigableSet<Clustering> toRemove = null;
        for (Clustering clustering : clusterings)
        {
            Row row = searchIter.next(clustering);
            if (row == null || !canRemoveRow(row, columns.regulars, sstableTimestamp))
                continue;

            if (toRemove == null)
                toRemove = new TreeSet<>(result.metadata().comparator);
            toRemove.add(clustering);
        }

        if (!removeStatic && toRemove == null)
            return filter;

        // Check if we have everything we need
        boolean hasNoMoreStatic = columns.statics.isEmpty() || removeStatic;
        boolean hasNoMoreClusterings = clusterings.isEmpty() || (toRemove != null && toRemove.size() == clusterings.size());
        if (hasNoMoreStatic && hasNoMoreClusterings)
            return null;

        if (toRemove != null)
        {
            BTreeSet.Builder<Clustering> newClusterings = BTreeSet.builder(result.metadata().comparator);
            newClusterings.addAll(Sets.difference(clusterings, toRemove));
            clusterings = newClusterings.build();
        }
        return new ClusteringIndexNamesFilter(clusterings, filter.isReversed());
    }

    private boolean canRemoveRow(Row row, Columns requestedColumns, long sstableTimestamp)
    {
        // We can remove a row if it has data that is more recent that the next sstable to consider for the data that the query
        // cares about. And the data we care about is 1) the row timestamp (since every query cares if the row exists or not)
        // and 2) the requested columns.
        if (row.primaryKeyLivenessInfo().isEmpty() || row.primaryKeyLivenessInfo().timestamp() <= sstableTimestamp)
            return false;

        for (ColumnDefinition column : requestedColumns)
        {
            Cell cell = row.getCell(column);
            if (cell == null || cell.timestamp() <= sstableTimestamp)
                return false;
        }
        return true;
    }

    @Override
    public boolean selectsFullPartition()
    {
        return metadata().isStaticCompactTable() ||
               (clusteringIndexFilter.selectsAllPartition() && !rowFilter().hasExpressionOnClusteringOrRegularColumns());
    }

    @Override
    public String toString()
    {
        return String.format("Read(%s.%s columns=%s rowFilter=%s limits=%s key=%s filter=%s, nowInSec=%d)",
                             metadata().ksName,
                             metadata().cfName,
                             columnFilter(),
                             rowFilter(),
                             limits(),
                             metadata().getKeyValidator().getString(partitionKey().getKey()),
                             clusteringIndexFilter.toString(metadata()),
                             nowInSec());
    }

    public MessageOut<ReadCommand> createMessage(int version)
    {
        return new MessageOut<>(MessagingService.Verb.READ, this, readSerializer);
    }

    public MessageOut<ReadCommand> createMessage(int version, InetAddress target)
    {
        return new MessageOut<>(MessagingService.Verb.READ, this, readSerializer, target, FacebookTracingImpl.QUERY_TYPE_READ, metadata().ksName, metadata().cfName);
    }

    protected void appendCQLWhereClause(StringBuilder sb)
    {
        sb.append(" WHERE ");

        sb.append(ColumnDefinition.toCQLString(metadata().partitionKeyColumns())).append(" = ");
        DataRange.appendKeyString(sb, metadata().getKeyValidator(), partitionKey().getKey());

        // We put the row filter first because the clustering index filter can end by "ORDER BY"
        if (!rowFilter().isEmpty())
            sb.append(" AND ").append(rowFilter());

        String filterString = clusteringIndexFilter().toCQLString(metadata());
        if (!filterString.isEmpty())
            sb.append(" AND ").append(filterString);
    }

    protected void serializeSelection(DataOutputPlus out, int version) throws IOException
    {
        metadata().getKeyValidator().writeValue(partitionKey().getKey(), out);
        ClusteringIndexFilter.serializer.serialize(clusteringIndexFilter(), out, version);
    }

    protected long selectionSerializedSize(int version)
    {
        return metadata().getKeyValidator().writtenLength(partitionKey().getKey())
             + ClusteringIndexFilter.serializer.serializedSize(clusteringIndexFilter(), version);
    }

    public boolean isLimitedToOnePartition()
    {
        return true;
    }

    /**
     * Groups multiple single partition read commands.
     */
    public static class Group implements ReadQuery
    {
        public final List<SinglePartitionReadCommand> commands;
        private final DataLimits limits;
        private final int nowInSec;
        private final boolean selectsFullPartitions;

        public Group(List<SinglePartitionReadCommand> commands, DataLimits limits)
        {
            assert !commands.isEmpty();
            this.commands = commands;
            this.limits = limits;
            SinglePartitionReadCommand firstCommand = commands.get(0);
            this.nowInSec = firstCommand.nowInSec();
            this.selectsFullPartitions = firstCommand.selectsFullPartition();
            for (int i = 1; i < commands.size(); i++)
                assert commands.get(i).nowInSec() == nowInSec;
        }

        public static Group one(SinglePartitionReadCommand command)
        {
            return new Group(Collections.<SinglePartitionReadCommand>singletonList(command), command.limits());
        }

        public PartitionIterator execute(ConsistencyLevel consistency, ClientState clientState) throws RequestExecutionException
        {
            return StorageProxy.read(this, consistency, clientState);
        }

        public int nowInSec()
        {
            return nowInSec;
        }

        public DataLimits limits()
        {
            return limits;
        }

        public CFMetaData metadata()
        {
            return commands.get(0).metadata();
        }

        @Override
        public boolean selectsFullPartition()
        {
            return selectsFullPartitions;
        }

        public ReadOrderGroup startOrderGroup()
        {
            // Note that the only difference between the command in a group must be the partition key on which
            // they applied. So as far as ReadOrderGroup is concerned, we can use any of the commands to start one.
            return commands.get(0).startOrderGroup();
        }

        public PartitionIterator executeInternal(ReadOrderGroup orderGroup)
        {
            List<PartitionIterator> partitions = new ArrayList<>(commands.size());
            for (SinglePartitionReadCommand cmd : commands)
                partitions.add(cmd.executeInternal(orderGroup));

            // Note that the only difference between the command in a group must be the partition key on which
            // they applied.
            boolean enforceStrictLiveness = commands.get(0).metadata().enforceStrictLiveness();
            // Because we only have enforce the limit per command, we need to enforce it globally.
            return limits.filter(PartitionIterators.concat(partitions),
                                 nowInSec,
                                 selectsFullPartitions,
                                 enforceStrictLiveness);
        }

        public QueryPager getPager(PagingState pagingState, int protocolVersion)
        {
            if (commands.size() == 1)
                return SinglePartitionReadCommand.getPager(commands.get(0), pagingState, protocolVersion);

            return new MultiPartitionPager(this, pagingState, protocolVersion);
        }

        public boolean selectsKey(DecoratedKey key)
        {
            return Iterables.any(commands, c -> c.selectsKey(key));
        }

        public boolean selectsClustering(DecoratedKey key, Clustering clustering)
        {
            return Iterables.any(commands, c -> c.selectsClustering(key, clustering));
        }

        @Override
        public String toString()
        {
            return commands.toString();
        }
    }

    private static class Deserializer extends SelectionDeserializer
    {
        public ReadCommand deserialize(DataInputPlus in,
                                       int version,
                                       boolean isDigest,
                                       int digestVersion,
                                       boolean isForThrift,
                                       CFMetaData metadata,
                                       int nowInSec,
                                       ColumnFilter columnFilter,
                                       RowFilter rowFilter,
                                       DataLimits limits,
                                       IndexMetadata index)
        throws IOException
        {
            DecoratedKey key = metadata.decorateKey(metadata.getKeyValidator().readValue(in, DatabaseDescriptor.getMaxValueSize()));
            ClusteringIndexFilter filter = ClusteringIndexFilter.serializer.deserialize(in, version, metadata);
            return new SinglePartitionReadCommand(isDigest, digestVersion, isForThrift, metadata, nowInSec, columnFilter, rowFilter, limits, key, filter, index);
        }
    }

    /**
     * {@code SSTableReaderListener} used to collect metrics about SSTable read access.
     */
    private static final class SSTableReadMetricsCollector implements SSTableReadsListener
    {
        /**
         * The number of SSTables that need to be merged. This counter is only updated for single partition queries
         * since this has been the behavior so far.
         */
        private int mergedSSTables;

        @Override
        public void onSSTableSelected(SSTableReader sstable, RowIndexEntry<?> indexEntry, SelectionReason reason)
        {
            sstable.incrementReadCount();
            mergedSSTables++;
        }

        /**
         * Returns the number of SSTables that need to be merged.
         * @return the number of SSTables that need to be merged.
         */
        public int getMergedSSTables()
        {
            return mergedSSTables;
        }
    }
}
