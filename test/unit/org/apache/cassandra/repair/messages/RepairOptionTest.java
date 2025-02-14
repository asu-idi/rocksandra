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

package org.apache.cassandra.repair.messages;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import com.google.common.collect.ImmutableMap;

import org.apache.cassandra.config.Config;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.dht.IPartitioner;
import org.apache.cassandra.dht.Murmur3Partitioner;
import org.apache.cassandra.dht.Range;
import org.apache.cassandra.dht.Token;
import org.apache.cassandra.repair.RepairParallelism;
import org.apache.cassandra.utils.FBUtilities;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RepairOptionTest
{
    @Test
    public void testParseOptions()
    {
        IPartitioner partitioner = Murmur3Partitioner.instance;
        Token.TokenFactory tokenFactory = partitioner.getTokenFactory();

        // parse with empty options
        RepairOption option = RepairOption.parse(new HashMap<String, String>(), partitioner);

        if (FBUtilities.isWindows() && (DatabaseDescriptor.getDiskAccessMode() != Config.DiskAccessMode.standard || DatabaseDescriptor.getIndexAccessMode() != Config.DiskAccessMode.standard))
            assertTrue(option.getParallelism() == RepairParallelism.PARALLEL);
        else
            assertTrue(option.getParallelism() == RepairParallelism.SEQUENTIAL);

        assertFalse(option.isPrimaryRange());
        assertFalse(option.isIncremental());

        // parse everything
        Map<String, String> options = new HashMap<>();
        options.put(RepairOption.PARALLELISM_KEY, "parallel");
        options.put(RepairOption.PRIMARY_RANGE_KEY, "false");
        options.put(RepairOption.INCREMENTAL_KEY, "false");
        options.put(RepairOption.RANGES_KEY, "0:10,11:20,21:30");
        options.put(RepairOption.COLUMNFAMILIES_KEY, "cf1,cf2,cf3");
        options.put(RepairOption.DATACENTERS_KEY, "dc1,dc2,dc3");
        options.put(RepairOption.HOSTS_KEY, "127.0.0.1,127.0.0.2,127.0.0.3");

        option = RepairOption.parse(options, partitioner);
        assertTrue(option.getParallelism() == RepairParallelism.PARALLEL);
        assertFalse(option.isPrimaryRange());
        assertFalse(option.isIncremental());

        Set<Range<Token>> expectedRanges = new HashSet<>(3);
        expectedRanges.add(new Range<>(tokenFactory.fromString("0"), tokenFactory.fromString("10")));
        expectedRanges.add(new Range<>(tokenFactory.fromString("11"), tokenFactory.fromString("20")));
        expectedRanges.add(new Range<>(tokenFactory.fromString("21"), tokenFactory.fromString("30")));
        assertEquals(expectedRanges, option.getRanges());

        Set<String> expectedCFs = new HashSet<>(3);
        expectedCFs.add("cf1");
        expectedCFs.add("cf2");
        expectedCFs.add("cf3");
        assertEquals(expectedCFs, option.getColumnFamilies());

        Set<String> expectedDCs = new HashSet<>(3);
        expectedDCs.add("dc1");
        expectedDCs.add("dc2");
        expectedDCs.add("dc3");
        assertEquals(expectedDCs, option.getDataCenters());

        Set<String> expectedHosts = new HashSet<>(3);
        expectedHosts.add("127.0.0.1");
        expectedHosts.add("127.0.0.2");
        expectedHosts.add("127.0.0.3");
        assertEquals(expectedHosts, option.getHosts());
    }

    @Test
    public void testPrWithLocalParseOptions()
    {
        DatabaseDescriptor.forceStaticInitialization();

        Map<String, String> options = new HashMap<>();
        options.put(RepairOption.PARALLELISM_KEY, "parallel");
        options.put(RepairOption.PRIMARY_RANGE_KEY, "true");
        options.put(RepairOption.INCREMENTAL_KEY, "false");
        options.put(RepairOption.COLUMNFAMILIES_KEY, "cf1,cf2,cf3");
        options.put(RepairOption.DATACENTERS_KEY, "datacenter1");

        RepairOption option = RepairOption.parse(options, Murmur3Partitioner.instance);
        assertTrue(option.isPrimaryRange());

        Set<String> expectedDCs = new HashSet<>(3);
        expectedDCs.add("datacenter1");
        assertEquals(expectedDCs, option.getDataCenters());
    }

    @Test
    public void testIncrementalRepairWithSubrangesIsNotGlobal() throws Exception
    {
        RepairOption ro = RepairOption.parse(ImmutableMap.of(RepairOption.INCREMENTAL_KEY, "true", RepairOption.RANGES_KEY, "41:42"),
                           Murmur3Partitioner.instance);
        assertFalse(ro.isGlobal());
        ro = RepairOption.parse(ImmutableMap.of(RepairOption.INCREMENTAL_KEY, "true", RepairOption.RANGES_KEY, ""),
                Murmur3Partitioner.instance);
        assertTrue(ro.isGlobal());
    }
}
