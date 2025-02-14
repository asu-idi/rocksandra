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

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.common.collect.Iterables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.config.ReadRepairDecision;
import org.apache.cassandra.exceptions.InvalidRequestException;
import org.apache.cassandra.exceptions.UnavailableException;
import org.apache.cassandra.locator.AbstractReplicationStrategy;
import org.apache.cassandra.locator.NetworkTopologyStrategy;
import org.apache.cassandra.transport.ProtocolException;

public enum ConsistencyLevel
{
    ANY         (0),
    ONE         (1),
    TWO         (2),
    THREE       (3),
    QUORUM      (4),
    ALL         (5),
    LOCAL_QUORUM(6, true),
    EACH_QUORUM (7),
    SERIAL      (8),
    LOCAL_SERIAL(9),
    LOCAL_ONE   (10, true);

    public enum Usage
    {
        READ
        {
            public int quorumFor(int replicationFactor)
            {
                return replicationFactor - replicationFactor / 2;
            }
        },

        WRITE
        {
            public int quorumFor(int replicationFactor)
            {
                return replicationFactor / 2 + 1;
            }
        };

        abstract int quorumFor(int replicaFactory);
    }

    private static final Logger logger = LoggerFactory.getLogger(ConsistencyLevel.class);

    // Used by the binary protocol
    public final int code;
    private final boolean isDCLocal;
    private static final ConsistencyLevel[] codeIdx;

    static
    {
        int maxCode = -1;
        for (ConsistencyLevel cl : ConsistencyLevel.values())
            maxCode = Math.max(maxCode, cl.code);
        codeIdx = new ConsistencyLevel[maxCode + 1];
        for (ConsistencyLevel cl : ConsistencyLevel.values())
        {
            if (codeIdx[cl.code] != null)
                throw new IllegalStateException("Duplicate code");
            codeIdx[cl.code] = cl;
        }
    }

    private ConsistencyLevel(int code)
    {
        this(code, false);
    }

    private ConsistencyLevel(int code, boolean isDCLocal)
    {
        this.code = code;
        this.isDCLocal = isDCLocal;
    }

    public static ConsistencyLevel fromCode(int code)
    {
        if (code < 0 || code >= codeIdx.length)
            throw new ProtocolException(String.format("Unknown code %d for a consistency level", code));
        return codeIdx[code];
    }

    private int quorumFor(Keyspace keyspace, Usage usage)
    {
        return usage.quorumFor(keyspace.getReplicationStrategy().getReplicationFactor());
    }

    private int localQuorumFor(Keyspace keyspace, String dc, Usage usage)
    {
        return (keyspace.getReplicationStrategy() instanceof NetworkTopologyStrategy)
               ? usage.quorumFor(((NetworkTopologyStrategy) keyspace.getReplicationStrategy()).getReplicationFactor(dc) )
               : quorumFor(keyspace, usage);
    }

    public int blockFor(Keyspace keyspace, Usage usage)
    {
        switch (this)
        {
            case ONE:
            case LOCAL_ONE:
                return 1;
            case ANY:
                return 1;
            case TWO:
                return 2;
            case THREE:
                return 3;
            case QUORUM:
            case SERIAL:
                return quorumFor(keyspace, usage);
            case ALL:
                return keyspace.getReplicationStrategy().getReplicationFactor();
            case LOCAL_QUORUM:
            case LOCAL_SERIAL:
                return localQuorumFor(keyspace, DatabaseDescriptor.getLocalDataCenter(), usage);
            case EACH_QUORUM:
                if (keyspace.getReplicationStrategy() instanceof NetworkTopologyStrategy)
                {
                    NetworkTopologyStrategy strategy = (NetworkTopologyStrategy) keyspace.getReplicationStrategy();
                    int n = 0;
                    for (String dc : strategy.getDatacenters())
                        n += localQuorumFor(keyspace, dc, usage);
                    return n;
                }
                else
                {
                    return quorumFor(keyspace, usage);
                }
            default:
                throw new UnsupportedOperationException("Invalid consistency level: " + toString());
        }
    }

    public int blockForWrite(Keyspace keyspace, Iterable<InetAddress> pending, Usage usage)
    {
        assert pending != null;

        int blockFor = blockFor(keyspace, usage);
        switch (this)
        {
            case ANY:
                break;
            case LOCAL_ONE: case LOCAL_QUORUM: case LOCAL_SERIAL:
                // we will only count local replicas towards our response count, as these queries only care about local guarantees
                blockFor += countLocalEndpoints(pending);
                break;
            case ONE: case TWO: case THREE:
            case QUORUM: case EACH_QUORUM:
            case SERIAL:
            case ALL:
                blockFor += Iterables.size(pending);
        }
        return blockFor;
    }

    public boolean isDatacenterLocal()
    {
        return isDCLocal;
    }

    public boolean isLocal(InetAddress endpoint)
    {
        return DatabaseDescriptor.getLocalDataCenter().equals(DatabaseDescriptor.getEndpointSnitch().getDatacenter(endpoint));
    }

    public int countLocalEndpoints(Iterable<InetAddress> liveEndpoints)
    {
        int count = 0;
        for (InetAddress endpoint : liveEndpoints)
            if (isLocal(endpoint))
                count++;
        return count;
    }

    private Map<String, Integer> countPerDCEndpoints(Keyspace keyspace, Iterable<InetAddress> liveEndpoints)
    {
        NetworkTopologyStrategy strategy = (NetworkTopologyStrategy) keyspace.getReplicationStrategy();

        Map<String, Integer> dcEndpoints = new HashMap<String, Integer>();
        for (String dc: strategy.getDatacenters())
            dcEndpoints.put(dc, 0);

        for (InetAddress endpoint : liveEndpoints)
        {
            String dc = DatabaseDescriptor.getEndpointSnitch().getDatacenter(endpoint);
            dcEndpoints.put(dc, dcEndpoints.get(dc) + 1);
        }
        return dcEndpoints;
    }

    public List<InetAddress> filterForQuery(Keyspace keyspace, List<InetAddress> liveEndpoints)
    {
        return filterForQuery(keyspace, liveEndpoints, ReadRepairDecision.NONE);
    }

    public List<InetAddress> filterForQuery(Keyspace keyspace, List<InetAddress> liveEndpoints, ReadRepairDecision readRepair)
    {
        /*
         * If we are doing an each quorum query, we have to make sure that the endpoints we select
         * provide a quorum for each data center. If we are not using a NetworkTopologyStrategy,
         * we should fall through and grab a quorum in the replication strategy.
         */
        if (this == EACH_QUORUM && keyspace.getReplicationStrategy() instanceof NetworkTopologyStrategy)
            return filterForEachQuorum(keyspace, liveEndpoints, readRepair);

        /*
         * Endpoints are expected to be restricted to live replicas, sorted by snitch preference.
         * For LOCAL_QUORUM, move local-DC replicas in front first as we need them there whether
         * we do read repair (since the first replica gets the data read) or not (since we'll take
         * the blockFor first ones).
         */
        if (isDCLocal)
            Collections.sort(liveEndpoints, DatabaseDescriptor.getLocalComparator());

        switch (readRepair)
        {
            case NONE:
                return liveEndpoints.subList(0, Math.min(liveEndpoints.size(), blockFor(keyspace, Usage.READ)));
            case GLOBAL:
                return liveEndpoints;
            case DC_LOCAL:
                List<InetAddress> local = new ArrayList<InetAddress>();
                List<InetAddress> other = new ArrayList<InetAddress>();
                for (InetAddress add : liveEndpoints)
                {
                    if (isLocal(add))
                        local.add(add);
                    else
                        other.add(add);
                }
                // check if blockfor more than we have localep's
                int blockFor = blockFor(keyspace, Usage.READ);
                if (local.size() < blockFor)
                    local.addAll(other.subList(0, Math.min(blockFor - local.size(), other.size())));
                return local;
            default:
                throw new AssertionError();
        }
    }

    private List<InetAddress> filterForEachQuorum(Keyspace keyspace, List<InetAddress> liveEndpoints, ReadRepairDecision readRepair)
    {
        NetworkTopologyStrategy strategy = (NetworkTopologyStrategy) keyspace.getReplicationStrategy();

        // quickly drop out if read repair is GLOBAL, since we just use all of the live endpoints
        if (readRepair == ReadRepairDecision.GLOBAL)
            return liveEndpoints;

        Map<String, List<InetAddress>> dcsEndpoints = new HashMap<>();
        for (String dc: strategy.getDatacenters())
            dcsEndpoints.put(dc, new ArrayList<>());

        for (InetAddress add : liveEndpoints)
        {
            String dc = DatabaseDescriptor.getEndpointSnitch().getDatacenter(add);
            dcsEndpoints.get(dc).add(add);
        }

        List<InetAddress> waitSet = new ArrayList<>();
        for (Map.Entry<String, List<InetAddress>> dcEndpoints : dcsEndpoints.entrySet())
        {
            List<InetAddress> dcEndpoint = dcEndpoints.getValue();
            if (readRepair == ReadRepairDecision.DC_LOCAL && dcEndpoints.getKey().equals(DatabaseDescriptor.getLocalDataCenter()))
                waitSet.addAll(dcEndpoint);
            else
                waitSet.addAll(dcEndpoint.subList(0, Math.min(localQuorumFor(keyspace, dcEndpoints.getKey(), Usage.READ), dcEndpoint.size())));
        }

        return waitSet;
    }

    public boolean isSufficientLiveNodes(Keyspace keyspace, Iterable<InetAddress> liveEndpoints, Usage usage)
    {
        switch (this)
        {
            case ANY:
                // local hint is acceptable, and local node is always live
                return true;
            case LOCAL_ONE:
                return countLocalEndpoints(liveEndpoints) >= 1;
            case LOCAL_QUORUM:
                return countLocalEndpoints(liveEndpoints) >= blockFor(keyspace, usage);
            case EACH_QUORUM:
                if (keyspace.getReplicationStrategy() instanceof NetworkTopologyStrategy)
                {
                    for (Map.Entry<String, Integer> entry : countPerDCEndpoints(keyspace, liveEndpoints).entrySet())
                    {
                        if (entry.getValue() < localQuorumFor(keyspace, entry.getKey(), usage))
                            return false;
                    }
                    return true;
                }
                // Fallthough on purpose for SimpleStrategy
            default:
                return Iterables.size(liveEndpoints) >= blockFor(keyspace, usage);
        }
    }

    public void assureSufficientLiveNodesForRead(Keyspace keyspace,
                                                 Iterable<InetAddress> liveEndpoints,
                                                 Usage usage) throws UnavailableException
    {
        assureSufficientLiveNodes(keyspace, liveEndpoints, usage, blockFor(keyspace, usage));
    }

    public void assureSufficientLiveNodesForWrite(Keyspace keyspace,
                                                  Iterable<InetAddress> liveEndpoints,
                                                  Usage usage,
                                                  Iterable<InetAddress> pendingEndpoints) throws UnavailableException
    {
        assureSufficientLiveNodes(keyspace, liveEndpoints, usage, blockForWrite(keyspace, pendingEndpoints, usage));
    }

    private void assureSufficientLiveNodes(Keyspace keyspace,
                                           Iterable<InetAddress> liveEndpoints,
                                           Usage usage,
                                           int blockFor) throws UnavailableException
    {
        switch (this)
        {
            case ANY:
                // local hint is acceptable, and local node is always live
                break;
            case LOCAL_ONE:
                if (countLocalEndpoints(liveEndpoints) == 0)
                    throw new UnavailableException(this, 1, 0);
                break;
            case LOCAL_QUORUM:
                int localLive = countLocalEndpoints(liveEndpoints);
                if (localLive < blockFor)
                {
                    if (logger.isTraceEnabled())
                    {
                        StringBuilder builder = new StringBuilder("Local replicas [");
                        for (InetAddress endpoint : liveEndpoints)
                        {
                            if (isLocal(endpoint))
                                builder.append(endpoint).append(",");
                        }
                        builder.append("] are insufficient to satisfy LOCAL_QUORUM requirement of ").append(blockFor).append(" live nodes in '").append(DatabaseDescriptor.getLocalDataCenter()).append("'");
                        logger.trace(builder.toString());
                    }
                    throw new UnavailableException(this, blockFor, localLive);
                }
                break;
            case EACH_QUORUM:
                if (keyspace.getReplicationStrategy() instanceof NetworkTopologyStrategy)
                {
                    for (Map.Entry<String, Integer> entry : countPerDCEndpoints(keyspace, liveEndpoints).entrySet())
                    {
                        int dcBlockFor = localQuorumFor(keyspace, entry.getKey(), usage);
                        int dcLive = entry.getValue();
                        if (dcLive < dcBlockFor)
                            throw new UnavailableException(this, entry.getKey(), dcBlockFor, dcLive);
                    }
                    break;
                }
                // Fallthough on purpose for SimpleStrategy
            default:
                int live = Iterables.size(liveEndpoints);
                if (live < blockFor)
                {
                    logger.trace("Live nodes {} do not satisfy ConsistencyLevel ({} required)", Iterables.toString(liveEndpoints), blockFor);
                    throw new UnavailableException(this, blockFor, live);
                }
                break;
        }
    }

    public void validateForRead(String keyspaceName) throws InvalidRequestException
    {
        switch (this)
        {
            case ANY:
                throw new InvalidRequestException("ANY ConsistencyLevel is only supported for writes");
        }
    }

    public void validateForWrite(String keyspaceName) throws InvalidRequestException
    {
        switch (this)
        {
            case SERIAL:
            case LOCAL_SERIAL:
                throw new InvalidRequestException("You must use conditional updates for serializable writes");
        }
    }

    // This is the same than validateForWrite really, but we include a slightly different error message for SERIAL/LOCAL_SERIAL
    public void validateForCasCommit(String keyspaceName) throws InvalidRequestException
    {
        switch (this)
        {
            case EACH_QUORUM:
                requireNetworkTopologyStrategy(keyspaceName);
                break;
            case SERIAL:
            case LOCAL_SERIAL:
                throw new InvalidRequestException(this + " is not supported as conditional update commit consistency. Use ANY if you mean \"make sure it is accepted but I don't care how many replicas commit it for non-SERIAL reads\"");
        }
    }

    public void validateForCas() throws InvalidRequestException
    {
        if (!isSerialConsistency())
            throw new InvalidRequestException("Invalid consistency for conditional update. Must be one of SERIAL or LOCAL_SERIAL");
    }

    public boolean isSerialConsistency()
    {
        return this == SERIAL || this == LOCAL_SERIAL;
    }

    public void validateCounterForWrite(CFMetaData metadata) throws InvalidRequestException
    {
        if (this == ConsistencyLevel.ANY)
            throw new InvalidRequestException("Consistency level ANY is not yet supported for counter table " + metadata.cfName);

        if (isSerialConsistency())
            throw new InvalidRequestException("Counter operations are inherently non-serializable");
    }

    private void requireNetworkTopologyStrategy(String keyspaceName) throws InvalidRequestException
    {
        AbstractReplicationStrategy strategy = Keyspace.open(keyspaceName).getReplicationStrategy();
        if (!(strategy instanceof NetworkTopologyStrategy))
            throw new InvalidRequestException(String.format("consistency level %s not compatible with replication strategy (%s)", this, strategy.getClass().getName()));
    }
}
