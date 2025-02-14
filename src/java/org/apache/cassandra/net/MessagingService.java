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
package org.apache.cassandra.net;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.net.*;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.util.*;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.security.cert.X509Certificate;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.Uninterruptibles;

import org.cliffc.high_scale_lib.NonBlockingHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.cassandra.concurrent.ExecutorLocals;
import org.apache.cassandra.concurrent.ScheduledExecutors;
import org.apache.cassandra.concurrent.Stage;
import org.apache.cassandra.concurrent.StageManager;
import org.apache.cassandra.concurrent.LocalAwareExecutorService;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.config.EncryptionOptions.ServerEncryptionOptions;
import org.apache.cassandra.db.*;
import org.apache.cassandra.batchlog.Batch;
import org.apache.cassandra.dht.AbstractBounds;
import org.apache.cassandra.dht.BootStrapper;
import org.apache.cassandra.dht.IPartitioner;
import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.cassandra.gms.EchoMessage;
import org.apache.cassandra.gms.GossipDigestAck;
import org.apache.cassandra.gms.GossipDigestAck2;
import org.apache.cassandra.gms.GossipDigestSyn;
import org.apache.cassandra.hints.HintMessage;
import org.apache.cassandra.hints.HintResponse;
import org.apache.cassandra.io.IVersionedSerializer;
import org.apache.cassandra.io.util.DataInputPlus;
import org.apache.cassandra.io.util.DataOutputPlus;
import org.apache.cassandra.io.util.FileUtils;
import org.apache.cassandra.locator.ILatencySubscriber;
import org.apache.cassandra.metrics.CassandraMetricsRegistry;
import org.apache.cassandra.metrics.ConnectionMetrics;
import org.apache.cassandra.metrics.DroppedMessageMetrics;
import org.apache.cassandra.metrics.MessagingMetrics;
import org.apache.cassandra.repair.messages.RepairMessage;
import org.apache.cassandra.security.SSLFactory;
import org.apache.cassandra.service.*;
import org.apache.cassandra.service.paxos.Commit;
import org.apache.cassandra.service.paxos.PrepareResponse;
import org.apache.cassandra.tracing.TraceState;
import org.apache.cassandra.tracing.Tracing;
import org.apache.cassandra.utils.*;
import org.apache.cassandra.utils.concurrent.SimpleCondition;

public final class MessagingService implements MessagingServiceMBean
{
    // Required to allow schema migrations while upgrading within the minor 3.0.x versions to 3.0.14.
    // See CASSANDRA-13004 for details.
    public final static boolean FORCE_3_0_PROTOCOL_VERSION = Boolean.getBoolean("cassandra.force_3_0_protocol_version");

    public static final String MBEAN_NAME = "org.apache.cassandra.net:type=MessagingService";

    // 8 bits version, so don't waste versions
    public static final int VERSION_12 = 6;
    public static final int VERSION_20 = 7;
    public static final int VERSION_21 = 8;
    public static final int VERSION_22 = 9;
    public static final int VERSION_30 = 10;
    public static final int VERSION_3014 = 11;
    public static final int current_version = FORCE_3_0_PROTOCOL_VERSION ? VERSION_30 : VERSION_3014;

    public static final String FAILURE_CALLBACK_PARAM = "CAL_BAC";
    public static final byte[] ONE_BYTE = new byte[1];
    public static final String FAILURE_RESPONSE_PARAM = "FAIL";

    /**
     * we preface every message with this number so the recipient can validate the sender is sane
     */
    public static final int PROTOCOL_MAGIC = 0xCA552DFA;

    private boolean allNodesAtLeast22 = true;
    private boolean allNodesAtLeast30 = true;

    public final MessagingMetrics metrics = new MessagingMetrics();

    /* All verb handler identifiers */
    public enum Verb
    {
        MUTATION,
        HINT,
        READ_REPAIR,
        READ,
        REQUEST_RESPONSE, // client-initiated reads and writes
        BATCH_STORE,  // was @Deprecated STREAM_INITIATE,
        BATCH_REMOVE, // was @Deprecated STREAM_INITIATE_DONE,
        @Deprecated STREAM_REPLY,
        @Deprecated STREAM_REQUEST,
        RANGE_SLICE,
        @Deprecated BOOTSTRAP_TOKEN,
        @Deprecated TREE_REQUEST,
        @Deprecated TREE_RESPONSE,
        @Deprecated JOIN,
        GOSSIP_DIGEST_SYN,
        GOSSIP_DIGEST_ACK,
        GOSSIP_DIGEST_ACK2,
        @Deprecated DEFINITIONS_ANNOUNCE,
        DEFINITIONS_UPDATE,
        TRUNCATE,
        SCHEMA_CHECK,
        @Deprecated INDEX_SCAN,
        REPLICATION_FINISHED,
        INTERNAL_RESPONSE, // responses to internal calls
        COUNTER_MUTATION,
        @Deprecated STREAMING_REPAIR_REQUEST,
        @Deprecated STREAMING_REPAIR_RESPONSE,
        SNAPSHOT, // Similar to nt snapshot
        MIGRATION_REQUEST,
        GOSSIP_SHUTDOWN,
        _TRACE, // dummy verb so we can use MS.droppedMessagesMap
        ECHO,
        REPAIR_MESSAGE,
        PAXOS_PREPARE,
        PAXOS_PROPOSE,
        PAXOS_COMMIT,
        @Deprecated PAGED_RANGE,
        // remember to add new verbs at the end, since we serialize by ordinal
        UNUSED_1,
        UNUSED_2,
        UNUSED_3,
        UNUSED_4,
        UNUSED_5,
        ;

        // This is to support a "late" choice of the verb based on the messaging service version.
        // See CASSANDRA-12249 for more details.
        public static Verb convertForMessagingServiceVersion(Verb verb, int version)
        {
            if (verb == PAGED_RANGE && version >= VERSION_30)
                return RANGE_SLICE;

            return verb;
        }
    }

    public static final Verb[] verbValues = Verb.values();

    public static final EnumMap<MessagingService.Verb, Stage> verbStages = new EnumMap<MessagingService.Verb, Stage>(MessagingService.Verb.class)
    {{
        put(Verb.MUTATION, Stage.MUTATION);
        put(Verb.COUNTER_MUTATION, Stage.COUNTER_MUTATION);
        put(Verb.READ_REPAIR, Stage.MUTATION);
        put(Verb.HINT, Stage.MUTATION);
        put(Verb.TRUNCATE, Stage.MUTATION);
        put(Verb.PAXOS_PREPARE, Stage.MUTATION);
        put(Verb.PAXOS_PROPOSE, Stage.MUTATION);
        put(Verb.PAXOS_COMMIT, Stage.MUTATION);
        put(Verb.BATCH_STORE, Stage.MUTATION);
        put(Verb.BATCH_REMOVE, Stage.MUTATION);

        put(Verb.READ, Stage.READ);
        put(Verb.RANGE_SLICE, Stage.READ);
        put(Verb.INDEX_SCAN, Stage.READ);
        put(Verb.PAGED_RANGE, Stage.READ);

        put(Verb.REQUEST_RESPONSE, Stage.REQUEST_RESPONSE);
        put(Verb.INTERNAL_RESPONSE, Stage.INTERNAL_RESPONSE);

        put(Verb.STREAM_REPLY, Stage.MISC); // actually handled by FileStreamTask and streamExecutors
        put(Verb.STREAM_REQUEST, Stage.MISC);
        put(Verb.REPLICATION_FINISHED, Stage.MISC);
        put(Verb.SNAPSHOT, Stage.MISC);

        put(Verb.TREE_REQUEST, Stage.ANTI_ENTROPY);
        put(Verb.TREE_RESPONSE, Stage.ANTI_ENTROPY);
        put(Verb.STREAMING_REPAIR_REQUEST, Stage.ANTI_ENTROPY);
        put(Verb.STREAMING_REPAIR_RESPONSE, Stage.ANTI_ENTROPY);
        put(Verb.REPAIR_MESSAGE, Stage.ANTI_ENTROPY);
        put(Verb.GOSSIP_DIGEST_ACK, Stage.GOSSIP);
        put(Verb.GOSSIP_DIGEST_ACK2, Stage.GOSSIP);
        put(Verb.GOSSIP_DIGEST_SYN, Stage.GOSSIP);
        put(Verb.GOSSIP_SHUTDOWN, Stage.GOSSIP);

        put(Verb.DEFINITIONS_UPDATE, Stage.MIGRATION);
        put(Verb.SCHEMA_CHECK, Stage.MIGRATION);
        put(Verb.MIGRATION_REQUEST, Stage.MIGRATION);
        put(Verb.INDEX_SCAN, Stage.READ);
        put(Verb.REPLICATION_FINISHED, Stage.MISC);
        put(Verb.SNAPSHOT, Stage.MISC);
        put(Verb.ECHO, Stage.GOSSIP);

        put(Verb.UNUSED_1, Stage.INTERNAL_RESPONSE);
        put(Verb.UNUSED_2, Stage.INTERNAL_RESPONSE);
        put(Verb.UNUSED_3, Stage.INTERNAL_RESPONSE);
    }};

    /**
     * Messages we receive in IncomingTcpConnection have a Verb that tells us what kind of message it is.
     * Most of the time, this is enough to determine how to deserialize the message payload.
     * The exception is the REQUEST_RESPONSE verb, which just means "a reply to something you told me to do."
     * Traditionally, this was fine since each VerbHandler knew what type of payload it expected, and
     * handled the deserialization itself.  Now that we do that in ITC, to avoid the extra copy to an
     * intermediary byte[] (See CASSANDRA-3716), we need to wire that up to the CallbackInfo object
     * (see below).
     */
    public static final EnumMap<Verb, IVersionedSerializer<?>> verbSerializers = new EnumMap<Verb, IVersionedSerializer<?>>(Verb.class)
    {{
        put(Verb.REQUEST_RESPONSE, CallbackDeterminedSerializer.instance);
        put(Verb.INTERNAL_RESPONSE, CallbackDeterminedSerializer.instance);

        put(Verb.MUTATION, Mutation.serializer);
        put(Verb.READ_REPAIR, Mutation.serializer);
        put(Verb.READ, ReadCommand.readSerializer);
        put(Verb.RANGE_SLICE, ReadCommand.rangeSliceSerializer);
        put(Verb.PAGED_RANGE, ReadCommand.pagedRangeSerializer);
        put(Verb.BOOTSTRAP_TOKEN, BootStrapper.StringSerializer.instance);
        put(Verb.REPAIR_MESSAGE, RepairMessage.serializer);
        put(Verb.GOSSIP_DIGEST_ACK, GossipDigestAck.serializer);
        put(Verb.GOSSIP_DIGEST_ACK2, GossipDigestAck2.serializer);
        put(Verb.GOSSIP_DIGEST_SYN, GossipDigestSyn.serializer);
        put(Verb.DEFINITIONS_UPDATE, MigrationManager.MigrationsSerializer.instance);
        put(Verb.TRUNCATE, Truncation.serializer);
        put(Verb.REPLICATION_FINISHED, null);
        put(Verb.COUNTER_MUTATION, CounterMutation.serializer);
        put(Verb.SNAPSHOT, SnapshotCommand.serializer);
        put(Verb.ECHO, EchoMessage.serializer);
        put(Verb.PAXOS_PREPARE, Commit.serializer);
        put(Verb.PAXOS_PROPOSE, Commit.serializer);
        put(Verb.PAXOS_COMMIT, Commit.serializer);
        put(Verb.HINT, HintMessage.serializer);
        put(Verb.BATCH_STORE, Batch.serializer);
        put(Verb.BATCH_REMOVE, UUIDSerializer.serializer);
    }};

    /**
     * A Map of what kind of serializer to wire up to a REQUEST_RESPONSE callback, based on outbound Verb.
     */
    public static final EnumMap<Verb, IVersionedSerializer<?>> callbackDeserializers = new EnumMap<Verb, IVersionedSerializer<?>>(Verb.class)
    {{
        put(Verb.MUTATION, WriteResponse.serializer);
        put(Verb.HINT, HintResponse.serializer);
        put(Verb.READ_REPAIR, WriteResponse.serializer);
        put(Verb.COUNTER_MUTATION, WriteResponse.serializer);
        put(Verb.RANGE_SLICE, ReadResponse.rangeSliceSerializer);
        put(Verb.PAGED_RANGE, ReadResponse.rangeSliceSerializer);
        put(Verb.READ, ReadResponse.serializer);
        put(Verb.TRUNCATE, TruncateResponse.serializer);
        put(Verb.SNAPSHOT, null);

        put(Verb.MIGRATION_REQUEST, MigrationManager.MigrationsSerializer.instance);
        put(Verb.SCHEMA_CHECK, UUIDSerializer.serializer);
        put(Verb.BOOTSTRAP_TOKEN, BootStrapper.StringSerializer.instance);
        put(Verb.REPLICATION_FINISHED, null);

        put(Verb.PAXOS_PREPARE, PrepareResponse.serializer);
        put(Verb.PAXOS_PROPOSE, BooleanSerializer.serializer);

        put(Verb.BATCH_STORE, WriteResponse.serializer);
        put(Verb.BATCH_REMOVE, WriteResponse.serializer);
    }};

    /* This records all the results mapped by message Id */
    private final ExpiringMap<Integer, CallbackInfo> callbacks;

    /**
     * a placeholder class that means "deserialize using the callback." We can't implement this without
     * special-case code in InboundTcpConnection because there is no way to pass the message id to IVersionedSerializer.
     */
    static class CallbackDeterminedSerializer implements IVersionedSerializer<Object>
    {
        public static final CallbackDeterminedSerializer instance = new CallbackDeterminedSerializer();

        public Object deserialize(DataInputPlus in, int version) throws IOException
        {
            throw new UnsupportedOperationException();
        }

        public void serialize(Object o, DataOutputPlus out, int version) throws IOException
        {
            throw new UnsupportedOperationException();
        }

        public long serializedSize(Object o, int version)
        {
            throw new UnsupportedOperationException();
        }
    }

    /* Lookup table for registering message handlers based on the verb. */
    private final Map<Verb, IVerbHandler> verbHandlers;

    private final ConcurrentMap<InetAddress, OutboundTcpConnectionPool> connectionManagers = new NonBlockingHashMap<>();

    private static final Logger logger = LoggerFactory.getLogger(MessagingService.class);
    private static final int LOG_DROPPED_INTERVAL_IN_MS = 5000;

    private final List<SocketThread> socketThreads = Lists.newArrayList();
    private final SimpleCondition listenGate;

    /**
     * Verbs it's okay to drop if the request has been queued longer than the request timeout.  These
     * all correspond to client requests or something triggered by them; we don't want to
     * drop internal messages like bootstrap or repair notifications.
     */
    public static final EnumSet<Verb> DROPPABLE_VERBS = EnumSet.of(Verb._TRACE,
                                                                   Verb.MUTATION,
                                                                   Verb.COUNTER_MUTATION,
                                                                   Verb.HINT,
                                                                   Verb.READ_REPAIR,
                                                                   Verb.READ,
                                                                   Verb.RANGE_SLICE,
                                                                   Verb.PAGED_RANGE,
                                                                   Verb.REQUEST_RESPONSE,
                                                                   Verb.BATCH_STORE,
                                                                   Verb.BATCH_REMOVE);


    private static final class DroppedMessages
    {
        final DroppedMessageMetrics metrics;
        final AtomicInteger droppedInternalTimeout;
        final AtomicInteger droppedCrossNodeTimeout;

        DroppedMessages(Verb verb)
        {
            this(new DroppedMessageMetrics(verb));
        }

        DroppedMessages(DroppedMessageMetrics metrics)
        {
            this.metrics = metrics;
            this.droppedInternalTimeout = new AtomicInteger(0);
            this.droppedCrossNodeTimeout = new AtomicInteger(0);
        }
    }

    @VisibleForTesting
    public void resetDroppedMessagesMap(String scope)
    {
        for (Verb verb : droppedMessagesMap.keySet())
            droppedMessagesMap.put(verb, new DroppedMessages(new DroppedMessageMetrics(metricName -> {
                return new CassandraMetricsRegistry.MetricName("DroppedMessages", metricName, scope);
            })));
    }

    // total dropped message counts for server lifetime
    private final Map<Verb, DroppedMessages> droppedMessagesMap = new EnumMap<>(Verb.class);

    private final List<ILatencySubscriber> subscribers = new ArrayList<ILatencySubscriber>();

    // protocol versions of the other nodes in the cluster
    private final ConcurrentMap<InetAddress, Integer> versions = new NonBlockingHashMap<InetAddress, Integer>();

    // message sinks are a testing hook
    private final Set<IMessageSink> messageSinks = new CopyOnWriteArraySet<>();

    public void addMessageSink(IMessageSink sink)
    {
        messageSinks.add(sink);
    }

    public void clearMessageSinks()
    {
        messageSinks.clear();
    }

    private static class MSHandle
    {
        public static final MessagingService instance = new MessagingService(false);
    }

    public static MessagingService instance()
    {
        return MSHandle.instance;
    }

    private static class MSTestHandle
    {
        public static final MessagingService instance = new MessagingService(true);
    }

    static MessagingService test()
    {
        return MSTestHandle.instance;
    }

    private MessagingService(boolean testOnly)
    {
        for (Verb verb : DROPPABLE_VERBS)
            droppedMessagesMap.put(verb, new DroppedMessages(verb));

        listenGate = new SimpleCondition();
        verbHandlers = new EnumMap<>(Verb.class);
        if (!testOnly)
        {
            Runnable logDropped = new Runnable()
            {
                public void run()
                {
                    logDroppedMessages();
                }
            };
            ScheduledExecutors.scheduledTasks.scheduleWithFixedDelay(logDropped, LOG_DROPPED_INTERVAL_IN_MS, LOG_DROPPED_INTERVAL_IN_MS, TimeUnit.MILLISECONDS);
        }

        Function<Pair<Integer, ExpiringMap.CacheableObject<CallbackInfo>>, ?> timeoutReporter = new Function<Pair<Integer, ExpiringMap.CacheableObject<CallbackInfo>>, Object>()
        {
            public Object apply(Pair<Integer, ExpiringMap.CacheableObject<CallbackInfo>> pair)
            {
                final CallbackInfo expiredCallbackInfo = pair.right.value;
                maybeAddLatency(expiredCallbackInfo.callback, expiredCallbackInfo.target, pair.right.timeout);
                ConnectionMetrics.totalTimeouts.mark();
                getConnectionPool(expiredCallbackInfo.target).incrementTimeout();
                if (expiredCallbackInfo.isFailureCallback())
                {
                    StageManager.getStage(Stage.INTERNAL_RESPONSE).submit(new Runnable() {
                        @Override
                        public void run() {
                            ((IAsyncCallbackWithFailure)expiredCallbackInfo.callback).onFailure(expiredCallbackInfo.target);
                        }
                    });
                }

                if (expiredCallbackInfo.shouldHint())
                {
                    Mutation mutation = ((WriteCallbackInfo) expiredCallbackInfo).mutation();
                    return StorageProxy.submitHint(mutation, expiredCallbackInfo.target, null);
                }

                return null;
            }
        };

        callbacks = new ExpiringMap<>(DatabaseDescriptor.getMinRpcTimeout(), timeoutReporter);

        if (!testOnly)
        {
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            try
            {
                mbs.registerMBean(this, new ObjectName(MBEAN_NAME));
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Track latency information for the dynamic snitch
     *
     * @param cb      the callback associated with this message -- this lets us know if it's a message type we're interested in
     * @param address the host that replied to the message
     * @param latency
     */
    public void maybeAddLatency(IAsyncCallback cb, InetAddress address, long latency)
    {
        if (cb.isLatencyForSnitch())
            addLatency(address, latency);
    }

    public void addLatency(InetAddress address, long latency)
    {
        for (ILatencySubscriber subscriber : subscribers)
            subscriber.receiveTiming(address, latency);
    }

    /**
     * called from gossiper when it notices a node is not responding.
     */
    public void convict(InetAddress ep)
    {
        logger.trace("Resetting pool for {}", ep);
        getConnectionPool(ep).reset();
    }

    public void listen()
    {
        callbacks.reset(); // hack to allow tests to stop/restart MS
        listen(FBUtilities.getLocalAddress());
        if (DatabaseDescriptor.shouldListenOnBroadcastAddress()
            && !FBUtilities.getLocalAddress().equals(FBUtilities.getBroadcastAddress()))
        {
            listen(FBUtilities.getBroadcastAddress());
        }
        listenGate.signalAll();
    }

    public static int waitServerIncomingConnectionSettle(int serverSocketPollMax)
    {
        if (!MessagingService.instance().isListening()) return 0;

        // By default, poll every second
        final int SERVER_SOCKET_POLL_INTERVAL_MS = Integer.getInteger("cassandra.messaging_service_settle_poll_interval_ms", 1000);

        // By default, wait at least 10 successes polls
        final int SERVER_SOCKET_POLL_SUCCESSES_REQUIRED = 10;

        // By default if the new incoming connection rate is <= 3 per poll interval, we consider it's settled for that interval
        final int SERVER_SOCKET_SETTLE_RATE = Integer.getInteger("cassandra.messaging_service_settle_new_conn_rate", 3);

        List<Integer> previousNumOfSockets = null;

        int numOkay = 0;
        int numPolls = 0;
        logger.info("Waiting for MessageService incoming connection to settle...");
        for (; numPolls < serverSocketPollMax; numPolls++)
        {
            if (numOkay >= SERVER_SOCKET_POLL_SUCCESSES_REQUIRED)
            {
                logger.info("MessagingService incoming connection settled after {} polls", numPolls);
                return numPolls;
            }
            Uninterruptibles.sleepUninterruptibly(SERVER_SOCKET_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);

            List<Integer> numOfSockets = MessagingService.instance().socketThreads.stream().map(st -> st.getNumSocketCreated()).collect(Collectors.toList());

            boolean isOkay = true;
            if (previousNumOfSockets == null || previousNumOfSockets.size() != numOfSockets.size())
            {
                isOkay = false;
            }
            else
            {
                for (int i = 0; i < previousNumOfSockets.size(); i++)
                {
                    int ratePerSecond = (numOfSockets.get(i) - previousNumOfSockets.get(i)) * 1000 / SERVER_SOCKET_POLL_INTERVAL_MS;
                    if (ratePerSecond > SERVER_SOCKET_SETTLE_RATE)
                    {
                        logger.debug("Waiting for MessagingService incoming connection to settle, waited polls: {}", numPolls);
                        isOkay = false;
                        break;
                    }
                }
            }
            numOkay = isOkay ? numOkay + 1 : 0;
            previousNumOfSockets = numOfSockets;
        }
        if (SERVER_SOCKET_POLL_SUCCESSES_REQUIRED < serverSocketPollMax)
            logger.info("MessagingService incoming connection is not settled but forced to start. Number of polls: {}", numPolls);
        else
            logger.info("Waited the max poll number: {}", numPolls);
        return numPolls;
    }

    /**
     * Listen on the specified port.
     *
     * @param localEp InetAddress whose port to listen on.
     */
    private void listen(InetAddress localEp) throws ConfigurationException
    {
        for (ServerSocket ss : getServerSockets(localEp))
        {
            SocketThread th = new SocketThread(ss, "ACCEPT-" + localEp);
            th.start();
            socketThreads.add(th);
        }
    }

    @SuppressWarnings("resource")
    private List<ServerSocket> getServerSockets(InetAddress localEp) throws ConfigurationException
    {
        final List<ServerSocket> ss = new ArrayList<ServerSocket>(2);

        ServerEncryptionOptions encryptionOptions = DatabaseDescriptor.getServerEncryptionOptions();
        if (encryptionOptions.enabled || encryptionOptions.internode_encryption != ServerEncryptionOptions.InternodeEncryption.none)
        {
            try
            {
                ss.add(SSLFactory.getServerSocket(DatabaseDescriptor.getServerEncryptionOptions(), localEp, DatabaseDescriptor.getSSLStoragePort()));
            }
            catch (IOException e)
            {
                throw new ConfigurationException("Unable to create ssl socket", e);
            }
            // setReuseAddress happens in the factory.
            logger.info("Starting Encrypted Messaging Service on SSL port {}", DatabaseDescriptor.getSSLStoragePort());
        }

        if (!encryptionOptions.enabled ||
            (encryptionOptions.enabled && encryptionOptions.optional) ||
            encryptionOptions.internode_encryption != ServerEncryptionOptions.InternodeEncryption.all)
        {
            ServerSocketChannel serverChannel = null;
            try
            {
                serverChannel = ServerSocketChannel.open();
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
            ServerSocket socket = serverChannel.socket();
            try
            {
                socket.setReuseAddress(true);
            }
            catch (SocketException e)
            {
                FileUtils.closeQuietly(socket);
                throw new ConfigurationException("Insufficient permissions to setReuseAddress", e);
            }
            InetSocketAddress address = new InetSocketAddress(localEp, DatabaseDescriptor.getStoragePort());
            try
            {
                socket.bind(address,500);
            }
            catch (BindException e)
            {
                FileUtils.closeQuietly(socket);
                if (e.getMessage().contains("in use"))
                    throw new ConfigurationException(address + " is in use by another process.  Change listen_address:storage_port in cassandra.yaml to values that do not conflict with other services");
                else if (e.getMessage().contains("Cannot assign requested address"))
                    throw new ConfigurationException("Unable to bind to address " + address
                                                     + ". Set listen_address in cassandra.yaml to an interface you can bind to, e.g., your private IP address on EC2");
                else
                    throw new RuntimeException(e);
            }
            catch (IOException e)
            {
                FileUtils.closeQuietly(socket);
                throw new RuntimeException(e);
            }
            String nic = FBUtilities.getNetworkInterface(localEp);
            logger.info("Starting Messaging Service on {}:{}{}", localEp, DatabaseDescriptor.getStoragePort(),
                        nic == null? "" : String.format(" (%s)", nic));
            ss.add(socket);
        }
        return ss;
    }

    public void waitUntilListening()
    {
        try
        {
            listenGate.await();
        }
        catch (InterruptedException ie)
        {
            logger.trace("await interrupted");
        }
    }

    public boolean isListening()
    {
        return listenGate.isSignaled();
    }

    public void destroyConnectionPool(InetAddress to)
    {
        OutboundTcpConnectionPool cp = connectionManagers.get(to);
        if (cp == null)
            return;
        cp.close();
        connectionManagers.remove(to);
    }

    public OutboundTcpConnectionPool getConnectionPool(InetAddress to)
    {
        OutboundTcpConnectionPool cp = connectionManagers.get(to);
        if (cp == null)
        {
            cp = new OutboundTcpConnectionPool(to);
            OutboundTcpConnectionPool existingPool = connectionManagers.putIfAbsent(to, cp);
            if (existingPool != null)
                cp = existingPool;
            else
                cp.start();
        }
        cp.waitForStarted();
        return cp;
    }


    public OutboundTcpConnection getConnection(InetAddress to, MessageOut msg)
    {
        return getConnectionPool(to).getConnection(msg);
    }

    /**
     * Register a verb and the corresponding verb handler with the
     * Messaging Service.
     *
     * @param verb
     * @param verbHandler handler for the specified verb
     */
    public void registerVerbHandlers(Verb verb, IVerbHandler verbHandler)
    {
        assert !verbHandlers.containsKey(verb);
        verbHandlers.put(verb, verbHandler);
    }

    /**
     * This method returns the verb handler associated with the registered
     * verb. If no handler has been registered then null is returned.
     *
     * @param type for which the verb handler is sought
     * @return a reference to IVerbHandler which is the handler for the specified verb
     */
    public IVerbHandler getVerbHandler(Verb type)
    {
        return verbHandlers.get(type);
    }

    public int addCallback(IAsyncCallback cb, MessageOut message, InetAddress to, long timeout, boolean failureCallback)
    {
        assert message.verb != Verb.MUTATION; // mutations need to call the overload with a ConsistencyLevel
        int messageId = nextId();
        CallbackInfo previous = callbacks.put(messageId, new CallbackInfo(to, cb, callbackDeserializers.get(message.verb), failureCallback), timeout);
        assert previous == null : String.format("Callback already exists for id %d! (%s)", messageId, previous);
        return messageId;
    }

    public int addCallback(IAsyncCallback cb,
                           MessageOut<?> message,
                           InetAddress to,
                           long timeout,
                           ConsistencyLevel consistencyLevel,
                           boolean allowHints)
    {
        assert message.verb == Verb.MUTATION
            || message.verb == Verb.COUNTER_MUTATION
            || message.verb == Verb.PAXOS_COMMIT;
        int messageId = nextId();

        CallbackInfo previous = callbacks.put(messageId,
                                              new WriteCallbackInfo(to,
                                                                    cb,
                                                                    message,
                                                                    callbackDeserializers.get(message.verb),
                                                                    consistencyLevel,
                                                                    allowHints),
                                                                    timeout);
        assert previous == null : String.format("Callback already exists for id %d! (%s)", messageId, previous);
        return messageId;
    }

    private static final AtomicInteger idGen = new AtomicInteger(0);

    private static int nextId()
    {
        return idGen.incrementAndGet();
    }

    public int sendRR(MessageOut message, InetAddress to, IAsyncCallback cb)
    {
        return sendRR(message, to, cb, message.getTimeout(), false);
    }

    public int sendRRWithFailure(MessageOut message, InetAddress to, IAsyncCallbackWithFailure cb)
    {
        return sendRR(message, to, cb, message.getTimeout(), true);
    }

    /**
     * Send a non-mutation message to a given endpoint. This method specifies a callback
     * which is invoked with the actual response.
     *
     * @param message message to be sent.
     * @param to      endpoint to which the message needs to be sent
     * @param cb      callback interface which is used to pass the responses or
     *                suggest that a timeout occurred to the invoker of the send().
     * @param timeout the timeout used for expiration
     * @return an reference to message id used to match with the result
     */
    public int sendRR(MessageOut message, InetAddress to, IAsyncCallback cb, long timeout, boolean failureCallback)
    {
        int id = addCallback(cb, message, to, timeout, failureCallback);
        sendOneWay(failureCallback ? message.withParameter(FAILURE_CALLBACK_PARAM, ONE_BYTE) : message, id, to);
        return id;
    }

    /**
     * Send a mutation message or a Paxos Commit to a given endpoint. This method specifies a callback
     * which is invoked with the actual response.
     * Also holds the message (only mutation messages) to determine if it
     * needs to trigger a hint (uses StorageProxy for that).
     *
     * @param message message to be sent.
     * @param to      endpoint to which the message needs to be sent
     * @param handler callback interface which is used to pass the responses or
     *                suggest that a timeout occurred to the invoker of the send().
     * @return an reference to message id used to match with the result
     */
    public int sendRR(MessageOut<?> message,
                      InetAddress to,
                      AbstractWriteResponseHandler<?> handler,
                      boolean allowHints)
    {
        int id = addCallback(handler, message, to, message.getTimeout(), handler.consistencyLevel, allowHints);
        sendOneWay(message.withParameter(FAILURE_CALLBACK_PARAM, ONE_BYTE), id, to);
        return id;
    }

    public void sendOneWay(MessageOut message, InetAddress to)
    {
        sendOneWay(message, nextId(), to);
    }

    public void sendReply(MessageOut message, int id, InetAddress to)
    {
        sendOneWay(message, id, to);
    }

    /**
     * Send a message to a given endpoint. This method adheres to the fire and forget
     * style messaging.
     *
     * @param message messages to be sent.
     * @param to      endpoint to which the message needs to be sent
     */
    public void sendOneWay(MessageOut message, int id, InetAddress to)
    {
        if (logger.isTraceEnabled())
            logger.trace("{} sending {} to {}@{}", FBUtilities.getBroadcastAddress(), message.verb, id, to);

        if (to.equals(FBUtilities.getBroadcastAddress()))
            logger.trace("Message-to-self {} going over MessagingService", message);

        // message sinks are a testing hook
        for (IMessageSink ms : messageSinks)
            if (!ms.allowOutgoingMessage(message, id, to))
                return;

        // get pooled connection (really, connection queue)
        OutboundTcpConnection connection = getConnection(to, message);

        // write it
        connection.enqueue(message, id);
    }

    public <T> AsyncOneResponse<T> sendRR(MessageOut message, InetAddress to)
    {
        AsyncOneResponse<T> iar = new AsyncOneResponse<T>();
        sendRR(message, to, iar);
        return iar;
    }

    public void register(ILatencySubscriber subcriber)
    {
        subscribers.add(subcriber);
    }

    public void clearCallbacksUnsafe()
    {
        callbacks.reset();
    }

    /**
     * Wait for callbacks and don't allow any more to be created (since they could require writing hints)
     */
    public void shutdown()
    {
        logger.info("Waiting for messaging service to quiesce");
        // We may need to schedule hints on the mutation stage, so it's erroneous to shut down the mutation stage first
        assert !StageManager.getStage(Stage.MUTATION).isShutdown();

        // the important part
        if (!callbacks.shutdownBlocking())
            logger.warn("Failed to wait for messaging service callbacks shutdown");

        // attempt to humor tests that try to stop and restart MS
        try
        {
            for (SocketThread th : socketThreads)
                try
                {
                    th.close();
                }
                catch (IOException e)
                {
                    // see https://issues.apache.org/jira/browse/CASSANDRA-10545
                    handleIOExceptionOnClose(e);
                }
        }
        catch (IOException e)
        {
            throw new IOError(e);
        }
    }

    public void receive(MessageIn message, int id, long timestamp, boolean isCrossNodeTimestamp)
    {
        TraceState state = Tracing.instance.initializeFromMessage(message);
        if (state != null)
            state.trace("{} message received from {}", message.verb, message.from);

        // message sinks are a testing hook
        for (IMessageSink ms : messageSinks)
            if (!ms.allowIncomingMessage(message, id))
                return;

        Runnable runnable = new MessageDeliveryTask(message, id, timestamp, isCrossNodeTimestamp);
        LocalAwareExecutorService stage = StageManager.getStage(message.getMessageType());
        assert stage != null : "No stage for message type " + message.verb;

        stage.execute(runnable, ExecutorLocals.create(state));
    }

    public void setCallbackForTests(int messageId, CallbackInfo callback)
    {
        callbacks.put(messageId, callback);
    }

    public CallbackInfo getRegisteredCallback(int messageId)
    {
        return callbacks.get(messageId);
    }

    public CallbackInfo removeRegisteredCallback(int messageId)
    {
        return callbacks.remove(messageId);
    }

    /**
     * @return System.nanoTime() when callback was created.
     */
    public long getRegisteredCallbackAge(int messageId)
    {
        return callbacks.getAge(messageId);
    }

    public static void validateMagic(int magic) throws IOException
    {
        if (magic != PROTOCOL_MAGIC)
            throw new IOException("invalid protocol header");
    }

    public static int getBits(int packed, int start, int count)
    {
        return packed >>> (start + 1) - count & ~(-1 << count);
    }

    public boolean areAllNodesAtLeast22()
    {
        return allNodesAtLeast22;
    }

    public boolean areAllNodesAtLeast30()
    {
        return allNodesAtLeast30;
    }

    /**
     * @return the last version associated with address, or @param version if this is the first such version
     */
    public int setVersion(InetAddress endpoint, int version)
    {
        logger.trace("Setting version {} for {}", version, endpoint);

        if (version < VERSION_22)
            allNodesAtLeast22 = false;
        if (version < VERSION_30)
            allNodesAtLeast30 = false;

        Integer v = versions.put(endpoint, version);

        // if the version was increased to 2.2 or later see if the min version across the cluster has changed
        if (v != null && (v < VERSION_30 && version >= VERSION_22))
            refreshAllNodeMinVersions();

        return v == null ? version : v;
    }

    public void resetVersion(InetAddress endpoint)
    {
        logger.trace("Resetting version for {}", endpoint);
        Integer removed = versions.remove(endpoint);
        if (removed != null && Math.min(removed, current_version) <= VERSION_30)
            refreshAllNodeMinVersions();
    }

    private void refreshAllNodeMinVersions()
    {
        boolean anyNodeLowerThan30 = false;
        for (Integer version : versions.values())
        {
            if (version < MessagingService.VERSION_30)
            {
                anyNodeLowerThan30 = true;
                allNodesAtLeast30 = false;
            }

            if (version < MessagingService.VERSION_22)
            {
                allNodesAtLeast22 = false;
                return;
            }
        }
        allNodesAtLeast22 = true;
        allNodesAtLeast30 = !anyNodeLowerThan30;
    }

    /**
     * Returns the messaging-version as announced by the given node but capped
     * to the min of the version as announced by the node and {@link #current_version}.
     */
    public int getVersion(InetAddress endpoint)
    {
        Integer v = versions.get(endpoint);
        if (v == null)
        {
            // we don't know the version. assume current. we'll know soon enough if that was incorrect.
            logger.trace("Assuming current protocol version for {}", endpoint);
            return MessagingService.current_version;
        }
        else
            return Math.min(v, MessagingService.current_version);
    }

    public int getVersion(String endpoint) throws UnknownHostException
    {
        return getVersion(InetAddress.getByName(endpoint));
    }

    /**
     * Returns the messaging-version exactly as announced by the given endpoint.
     */
    public int getRawVersion(InetAddress endpoint)
    {
        Integer v = versions.get(endpoint);
        if (v == null)
            throw new IllegalStateException("getRawVersion() was called without checking knowsVersion() result first");
        return v;
    }

    public boolean knowsVersion(InetAddress endpoint)
    {
        return versions.containsKey(endpoint);
    }

    public void incrementDroppedMessages(Verb verb)
    {
        incrementDroppedMessages(verb, false);
    }

    public void incrementDroppedMessages(Verb verb, boolean isCrossNodeTimeout)
    {
        assert DROPPABLE_VERBS.contains(verb) : "Verb " + verb + " should not legally be dropped";
        incrementDroppedMessages(droppedMessagesMap.get(verb), isCrossNodeTimeout);
    }

    private void incrementDroppedMessages(DroppedMessages droppedMessages, boolean isCrossNodeTimeout)
    {
        droppedMessages.metrics.dropped.mark();
        if (isCrossNodeTimeout)
            droppedMessages.droppedCrossNodeTimeout.incrementAndGet();
        else
            droppedMessages.droppedInternalTimeout.incrementAndGet();
    }

    private void logDroppedMessages()
    {
        List<String> logs = getDroppedMessagesLogs();
        for (String log : logs)
            logger.info(log);

        if (logs.size() > 0)
            StatusLogger.log();
    }

    @VisibleForTesting
    List<String> getDroppedMessagesLogs()
    {
        List<String> ret = new ArrayList<>();
        for (Map.Entry<Verb, DroppedMessages> entry : droppedMessagesMap.entrySet())
        {
            Verb verb = entry.getKey();
            DroppedMessages droppedMessages = entry.getValue();

            int droppedInternalTimeout = droppedMessages.droppedInternalTimeout.getAndSet(0);
            int droppedCrossNodeTimeout = droppedMessages.droppedCrossNodeTimeout.getAndSet(0);
            if (droppedInternalTimeout > 0 || droppedCrossNodeTimeout > 0)
            {
                ret.add(String.format("%s messages were dropped in last %d ms: %d for internal timeout and %d for cross node timeout",
                                      verb,
                                      LOG_DROPPED_INTERVAL_IN_MS,
                                      droppedInternalTimeout,
                                      droppedCrossNodeTimeout));
            }
        }
        return ret;
    }

    @VisibleForTesting
    public static class IncomingConnectionBuilder extends Thread
    {
        private final Socket socket;
        private final Set<Closeable> connections;

        IncomingConnectionBuilder(Socket socket, Set<Closeable> connections)
        {
            super("MessagingService-Building-Incoming-" + socket.getInetAddress());
            this.socket = socket;
            this.connections = connections;
        }

        public void run()
        {
            try
            {
                if (!authenticate())
                {
                    logger.trace("remote failed to authenticate");
                    socket.close();
                    return;
                }

                socket.setKeepAlive(true);
                socket.setSoTimeout(2 * OutboundTcpConnection.WAIT_FOR_VERSION_MAX_TIME);
                // determine the connection type to decide whether to buffer
                DataInputStream in = new DataInputStream(socket.getInputStream());
                MessagingService.validateMagic(in.readInt());
                int header = in.readInt();
                boolean isStream = MessagingService.getBits(header, 3, 1) == 1;
                int version = MessagingService.getBits(header, 15, 8);
                logger.trace("Connection version {} from {}", version, socket.getInetAddress());
                socket.setSoTimeout(0);

                Thread thread = isStream
                                ? new IncomingStreamingConnection(version, socket, connections)
                                : new IncomingTcpConnection(version, MessagingService.getBits(header, 2, 1) == 1, socket, connections);
                thread.start();
                connections.add((Closeable) thread);
            }
            catch (AsynchronousCloseException e)
            {
                // this happens when another thread calls close().
                logger.trace("Asynchronous close seen by server thread");
            }
            catch (ClosedChannelException e)
            {
                logger.trace("MessagingService server thread already closed");
            }
            catch (SSLHandshakeException e)
            {
                logger.error("SSL handshake error for inbound connection from " + socket, e);
                FileUtils.closeQuietly(socket);
            }
            catch (Throwable t)
            {
                logger.trace("Error reading the socket {}", socket, t);
                FileUtils.closeQuietly(socket);
            }
        }

        private boolean authenticate()
        {
          X509Certificate[] certificates = null;
          if (socket instanceof SSLSocket) {
            try {
              certificates = ((SSLSocket) socket).getSession().getPeerCertificateChain();
            }
            catch (SSLPeerUnverifiedException e){
              logger.warn("Failed to get peer certificates for peer {}", socket.getInetAddress(), e);
            }
          }

          return DatabaseDescriptor.getInternodeAuthenticator().authenticate(
            certificates, socket.getInetAddress(), socket.getPort());
        }
    }

    @VisibleForTesting
    public static class SocketThread extends Thread
    {
        private final ServerSocket server;
        @VisibleForTesting
        public final Set<Closeable> connections = Sets.newConcurrentHashSet();

        SocketThread(ServerSocket server, String name)
        {
            super(name);
            this.server = server;
        }

        private volatile int numSocketCreated = 0;

        public int getNumSocketCreated()
        {
            return numSocketCreated;
        }

        @VisibleForTesting
        public void bumpNumSocketCreatedForTest()
        {
            numSocketCreated++;
        }

        @SuppressWarnings("resource")
        public void run()
        {
            while (!server.isClosed())
            {
                Socket socket = null;
                try
                {
                    socket = server.accept();
                    numSocketCreated++;
                    Thread thread = new IncomingConnectionBuilder(socket, connections);
                    thread.start();
                }
                catch (IOException t)
                {
                    logger.trace("Error reading the socket {}", socket, t);
                    FileUtils.closeQuietly(socket);
                }
            }
            logger.info("MessagingService has terminated the accept() thread");
        }

        void close() throws IOException
        {
            logger.trace("Closing accept() thread");

            try
            {
                server.close();
            }
            catch (IOException e)
            {
                // see https://issues.apache.org/jira/browse/CASSANDRA-8220
                // see https://issues.apache.org/jira/browse/CASSANDRA-12513
                handleIOExceptionOnClose(e);
            }
            for (Closeable connection : connections)
            {
                connection.close();
            }
        }
    }

    private static void handleIOExceptionOnClose(IOException e) throws IOException
    {
        // dirty hack for clean shutdown on OSX w/ Java >= 1.8.0_20
        // see https://bugs.openjdk.java.net/browse/JDK-8050499;
        // also CASSANDRA-12513
        if ("Mac OS X".equals(System.getProperty("os.name")))
        {
            switch (e.getMessage())
            {
                case "Unknown error: 316":
                case "No such file or directory":
                    return;
            }
        }

        throw e;
    }

    public Map<String, Integer> getLargeMessagePendingTasks()
    {
        Map<String, Integer> pendingTasks = new HashMap<String, Integer>(connectionManagers.size());
        for (Map.Entry<InetAddress, OutboundTcpConnectionPool> entry : connectionManagers.entrySet())
            pendingTasks.put(entry.getKey().getHostAddress(), entry.getValue().largeMessages.getPendingMessages());
        return pendingTasks;
    }

    public int getLargeMessagePendingTasks(InetAddress address)
    {
        OutboundTcpConnectionPool connection = connectionManagers.get(address);
        return connection == null ? 0 : connection.largeMessages.getPendingMessages();
    }

    public Map<String, Long> getLargeMessageCompletedTasks()
    {
        Map<String, Long> completedTasks = new HashMap<String, Long>(connectionManagers.size());
        for (Map.Entry<InetAddress, OutboundTcpConnectionPool> entry : connectionManagers.entrySet())
            completedTasks.put(entry.getKey().getHostAddress(), entry.getValue().largeMessages.getCompletedMesssages());
        return completedTasks;
    }

    public Map<String, Long> getLargeMessageDroppedTasks()
    {
        Map<String, Long> droppedTasks = new HashMap<String, Long>(connectionManagers.size());
        for (Map.Entry<InetAddress, OutboundTcpConnectionPool> entry : connectionManagers.entrySet())
            droppedTasks.put(entry.getKey().getHostAddress(), entry.getValue().largeMessages.getDroppedMessages());
        return droppedTasks;
    }

    public Map<String, Integer> getSmallMessagePendingTasks()
    {
        Map<String, Integer> pendingTasks = new HashMap<String, Integer>(connectionManagers.size());
        for (Map.Entry<InetAddress, OutboundTcpConnectionPool> entry : connectionManagers.entrySet())
            pendingTasks.put(entry.getKey().getHostAddress(), entry.getValue().smallMessages.getPendingMessages());
        return pendingTasks;
    }

    public Map<String, Long> getSmallMessageCompletedTasks()
    {
        Map<String, Long> completedTasks = new HashMap<String, Long>(connectionManagers.size());
        for (Map.Entry<InetAddress, OutboundTcpConnectionPool> entry : connectionManagers.entrySet())
            completedTasks.put(entry.getKey().getHostAddress(), entry.getValue().smallMessages.getCompletedMesssages());
        return completedTasks;
    }

    public Map<String, Long> getSmallMessageDroppedTasks()
    {
        Map<String, Long> droppedTasks = new HashMap<String, Long>(connectionManagers.size());
        for (Map.Entry<InetAddress, OutboundTcpConnectionPool> entry : connectionManagers.entrySet())
            droppedTasks.put(entry.getKey().getHostAddress(), entry.getValue().smallMessages.getDroppedMessages());
        return droppedTasks;
    }

    public Map<String, Integer> getGossipMessagePendingTasks()
    {
        Map<String, Integer> pendingTasks = new HashMap<String, Integer>(connectionManagers.size());
        for (Map.Entry<InetAddress, OutboundTcpConnectionPool> entry : connectionManagers.entrySet())
            pendingTasks.put(entry.getKey().getHostAddress(), entry.getValue().gossipMessages.getPendingMessages());
        return pendingTasks;
    }

    public Map<String, Long> getGossipMessageCompletedTasks()
    {
        Map<String, Long> completedTasks = new HashMap<String, Long>(connectionManagers.size());
        for (Map.Entry<InetAddress, OutboundTcpConnectionPool> entry : connectionManagers.entrySet())
            completedTasks.put(entry.getKey().getHostAddress(), entry.getValue().gossipMessages.getCompletedMesssages());
        return completedTasks;
    }

    public Map<String, Long> getGossipMessageDroppedTasks()
    {
        Map<String, Long> droppedTasks = new HashMap<String, Long>(connectionManagers.size());
        for (Map.Entry<InetAddress, OutboundTcpConnectionPool> entry : connectionManagers.entrySet())
            droppedTasks.put(entry.getKey().getHostAddress(), entry.getValue().gossipMessages.getDroppedMessages());
        return droppedTasks;
    }

    public Map<String, Integer> getDroppedMessages()
    {
        Map<String, Integer> map = new HashMap<>(droppedMessagesMap.size());
        for (Map.Entry<Verb, DroppedMessages> entry : droppedMessagesMap.entrySet())
            map.put(entry.getKey().toString(), (int) entry.getValue().metrics.dropped.getCount());
        return map;
    }


    public long getTotalTimeouts()
    {
        return ConnectionMetrics.totalTimeouts.getCount();
    }

    public Map<String, Long> getTimeoutsPerHost()
    {
        Map<String, Long> result = new HashMap<String, Long>(connectionManagers.size());
        for (Map.Entry<InetAddress, OutboundTcpConnectionPool> entry: connectionManagers.entrySet())
        {
            String ip = entry.getKey().getHostAddress();
            long recent = entry.getValue().getTimeouts();
            result.put(ip, recent);
        }
        return result;
    }

    public static IPartitioner globalPartitioner()
    {
        return StorageService.instance.getTokenMetadata().partitioner;
    }

    public static void validatePartitioner(Collection<? extends AbstractBounds<?>> allBounds)
    {
        for (AbstractBounds<?> bounds : allBounds)
            validatePartitioner(bounds);
    }

    public static void validatePartitioner(AbstractBounds<?> bounds)
    {
        if (globalPartitioner() != bounds.left.getPartitioner())
            throw new AssertionError(String.format("Partitioner in bounds serialization. Expected %s, was %s.",
                                                   globalPartitioner().getClass().getName(),
                                                   bounds.left.getPartitioner().getClass().getName()));
    }

    @VisibleForTesting
    public List<SocketThread> getSocketThreads()
    {
        return socketThreads;
    }
}
