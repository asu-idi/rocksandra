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
package org.apache.cassandra.streaming;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.BeforeClass;
import org.junit.After;
import org.junit.Test;

import junit.framework.Assert;
import org.apache.cassandra.SchemaLoader;
import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.db.Keyspace;
import org.apache.cassandra.dht.Range;
import org.apache.cassandra.dht.Token;
import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.cassandra.io.sstable.format.SSTableReader;
import org.apache.cassandra.schema.KeyspaceParams;
import org.apache.cassandra.streaming.messages.OutgoingFileMessage;
import org.apache.cassandra.streaming.messages.OutgoingMessage;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.utils.concurrent.Ref;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class StreamTransferTaskTest
{
    public static final String KEYSPACE1 = "StreamTransferTaskTest";
    public static final String CF_STANDARD = "Standard1";

    @BeforeClass
    public static void defineSchema() throws ConfigurationException
    {
        SchemaLoader.prepareServer();
        SchemaLoader.createKeyspace(KEYSPACE1,
                                    KeyspaceParams.simple(1),
                                    SchemaLoader.standardCFMD(KEYSPACE1, CF_STANDARD));
    }

    @After
    public void tearDown()
    {
        ColumnFamilyStore cfs = Keyspace.open(KEYSPACE1).getColumnFamilyStore(CF_STANDARD);
        cfs.clearUnsafe();
    }

    @Test
    public void testScheduleTimeout() throws Exception
    {
        InetAddress peer = FBUtilities.getBroadcastAddress();
        StreamSession session = new StreamSession(peer, peer, null, 0, true, false);
        ColumnFamilyStore cfs = Keyspace.open(KEYSPACE1).getColumnFamilyStore(CF_STANDARD);

        // create two sstables
        for (int i = 0; i < 2; i++)
        {
            SchemaLoader.insertData(KEYSPACE1, CF_STANDARD, i, 1);
            cfs.forceBlockingFlush();
        }

        // create streaming task that streams those two sstables
        StreamTransferTask task = new StreamTransferTask(session, cfs.metadata.cfId);
        for (SSTableReader sstable : cfs.getLiveSSTables())
        {
            List<Range<Token>> ranges = new ArrayList<>();
            ranges.add(new Range<>(sstable.first.getToken(), sstable.last.getToken()));
            task.addTransferFile(sstable.selfRef(), 1, sstable.getPositionsForRanges(ranges), 0);
        }
        assertEquals(2, task.getTotalNumberOfFiles());

        // if file sending completes before timeout then the task should be canceled.
        Future f = task.scheduleTimeout(0, 0, TimeUnit.NANOSECONDS);
        f.get();

        // when timeout runs on second file, task should be completed
        f = task.scheduleTimeout(1, 10, TimeUnit.MILLISECONDS);
        task.complete(1);
        try
        {
            f.get();
            Assert.assertTrue(false);
        }
        catch (CancellationException ex)
        {
        }

        assertEquals(StreamSession.State.WAIT_COMPLETE, session.state());

        // when all streaming are done, time out task should not be scheduled.
        assertNull(task.scheduleTimeout(1, 1, TimeUnit.SECONDS));
    }

    @Test
    public void testFailSessionDuringTransferShouldNotReleaseReferences() throws Exception
    {
        InetAddress peer = FBUtilities.getBroadcastAddress();
        StreamCoordinator streamCoordinator = new StreamCoordinator(1, true, false, null);
        StreamResultFuture future = StreamResultFuture.init(UUID.randomUUID(), "", Collections.<StreamEventHandler>emptyList(), streamCoordinator);
        StreamSession session = new StreamSession(peer, peer, null, 0, true, false);
        session.init(future);
        ColumnFamilyStore cfs = Keyspace.open(KEYSPACE1).getColumnFamilyStore(CF_STANDARD);

        // create two sstables
        for (int i = 0; i < 2; i++)
        {
            SchemaLoader.insertData(KEYSPACE1, CF_STANDARD, i, 1);
            cfs.forceBlockingFlush();
        }

        // create streaming task that streams those two sstables
        StreamTransferTask task = new StreamTransferTask(session, cfs.metadata.cfId);
        List<Ref<SSTableReader>> refs = new ArrayList<>(cfs.getLiveSSTables().size());
        for (SSTableReader sstable : cfs.getLiveSSTables())
        {
            List<Range<Token>> ranges = new ArrayList<>();
            ranges.add(new Range<>(sstable.first.getToken(), sstable.last.getToken()));
            Ref<SSTableReader> ref = sstable.selfRef();
            refs.add(ref);
            task.addTransferFile(ref, 1, sstable.getPositionsForRanges(ranges), 0);
        }
        assertEquals(2, task.getTotalNumberOfFiles());

        //add task to stream session, so it is aborted when stream session fails
        session.transfers.put(UUID.randomUUID(), task);

        //make a copy of outgoing file messages, since task is cleared when it's aborted
        Collection<OutgoingMessage> files = new LinkedList<OutgoingMessage>(task.files.values());

        //simulate start transfer
        for (OutgoingMessage file : files)
        {
            file.startTransfer();
        }

        //fail stream session mid-transfer
        session.onError(new Exception("Fake exception"));

        //make sure reference was not released
        for (Ref<SSTableReader> ref : refs)
        {
            assertEquals(1, ref.globalCount());
        }

        //simulate finish transfer
        for (OutgoingMessage file : files)
        {
            file.finishTransfer();
        }

        //now reference should be released
        for (Ref<SSTableReader> ref : refs)
        {
            assertEquals(0, ref.globalCount());
        }
    }
}
