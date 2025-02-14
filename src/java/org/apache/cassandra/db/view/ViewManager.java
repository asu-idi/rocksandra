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
package org.apache.cassandra.db.view;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;

import com.google.common.util.concurrent.Striped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.config.ViewDefinition;
import org.apache.cassandra.db.*;
import org.apache.cassandra.db.rows.*;
import org.apache.cassandra.db.partitions.*;


/**
 * Manages {@link View}'s for a single {@link ColumnFamilyStore}. All of the views for that table are created when this
 * manager is initialized.
 *
 * The main purposes of the manager are to provide a single location for updates to be vetted to see whether they update
 * any views {@link ViewManager#updatesAffectView(Collection, boolean)}, provide locks to prevent multiple
 * updates from creating incoherent updates in the view {@link ViewManager#acquireLockFor(ByteBuffer)}, and
 * to affect change on the view.
 *
 * TODO: I think we can get rid of that class. For addition/removal of view by names, we could move it Keyspace. And we
 * not sure it's even worth keeping viewsByName as none of the related operation are performance sensitive so we could
 * find the view by iterating over the CFStore.viewManager directly.
 * For the lock, it could move to Keyspace too, but I don't remmenber why it has to be at the keyspace level and if it
 * can be at the table level, maybe that's where it should be.
 */
public class ViewManager
{
    private static final Logger logger = LoggerFactory.getLogger(ViewManager.class);

    private static final Striped<Lock> LOCKS = Striped.lazyWeakLock(DatabaseDescriptor.getConcurrentViewWriters() * 1024);

    private static final boolean enableCoordinatorBatchlog = Boolean.getBoolean("cassandra.mv_enable_coordinator_batchlog");

    private final ConcurrentMap<String, View> viewsByName = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, TableViews> viewsByBaseTable = new ConcurrentHashMap<>();
    private final Keyspace keyspace;

    public ViewManager(Keyspace keyspace)
    {
        this.keyspace = keyspace;
    }

    public boolean updatesAffectView(Collection<? extends IMutation> mutations, boolean coordinatorBatchlog)
    {
        if (coordinatorBatchlog && !enableCoordinatorBatchlog)
            return false;

        for (IMutation mutation : mutations)
        {
            for (PartitionUpdate update : mutation.getPartitionUpdates())
            {
                assert keyspace.getName().equals(update.metadata().ksName);

                if (coordinatorBatchlog && keyspace.getReplicationStrategy().getReplicationFactor() == 1)
                    continue;

                if (!forTable(update.metadata()).updatedViews(update).isEmpty())
                    return true;
            }
        }

        return false;
    }

    private Iterable<View> allViews()
    {
        return viewsByName.values();
    }

    public void update(String viewName)
    {
        View view = viewsByName.get(viewName);
        assert view != null : "When updating a view, it should already be in the ViewManager";
        view.build();

        // We provide the new definition from the base metadata
        Optional<ViewDefinition> viewDefinition = keyspace.getMetadata().views.get(viewName);
        assert viewDefinition.isPresent() : "When updating a view, it should still be in the Keyspaces views";
        view.updateDefinition(viewDefinition.get());
    }

    public void reload()
    {
        Map<String, ViewDefinition> newViewsByName = new HashMap<>();
        for (ViewDefinition definition : keyspace.getMetadata().views)
        {
            newViewsByName.put(definition.viewName, definition);
        }

        for (String viewName : viewsByName.keySet())
        {
            if (!newViewsByName.containsKey(viewName))
                removeView(viewName);
        }

        for (Map.Entry<String, ViewDefinition> entry : newViewsByName.entrySet())
        {
            if (!viewsByName.containsKey(entry.getKey()))
                addView(entry.getValue());
        }

        for (View view : allViews())
        {
            view.build();
            // We provide the new definition from the base metadata
            view.updateDefinition(newViewsByName.get(view.name));
        }
    }

    public void addView(ViewDefinition definition)
    {
        // Skip if the base table doesn't exist due to schema propagation issues, see CASSANDRA-13737
        if (!keyspace.hasColumnFamilyStore(definition.baseTableId))
        {
            logger.warn("Not adding view {} because the base table {} is unknown",
                        definition.viewName,
                        definition.baseTableId);
            return;
        }

        View view = new View(definition, keyspace.getColumnFamilyStore(definition.baseTableId));
        forTable(view.getDefinition().baseTableMetadata()).add(view);
        viewsByName.put(definition.viewName, view);
    }

    public void removeView(String name)
    {
        View view = viewsByName.remove(name);

        if (view == null)
            return;

        forTable(view.getDefinition().baseTableMetadata()).removeByName(name);
        SystemKeyspace.setViewRemoved(keyspace.getName(), view.name);
    }

    public View getByName(String name)
    {
        return viewsByName.get(name);
    }

    public void buildAllViews()
    {
        for (View view : allViews())
            view.build();
    }

    public TableViews forTable(CFMetaData metadata)
    {
        UUID baseId = metadata.cfId;
        TableViews views = viewsByBaseTable.get(baseId);
        if (views == null)
        {
            views = new TableViews(metadata);
            TableViews previous = viewsByBaseTable.putIfAbsent(baseId, views);
            if (previous != null)
                views = previous;
        }
        return views;
    }

    public static Lock acquireLockFor(ByteBuffer key)
    {
        Lock lock = LOCKS.get(key);

        if (lock.tryLock())
            return lock;

        return null;
    }
}
