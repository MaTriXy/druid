/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.indexing.overlord;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.inject.Inject;
import org.apache.druid.error.DruidException;
import org.apache.druid.indexer.TaskInfo;
import org.apache.druid.indexer.TaskStatus;
import org.apache.druid.indexer.TaskStatusPlus;
import org.apache.druid.indexing.common.TaskLock;
import org.apache.druid.indexing.common.actions.TaskAction;
import org.apache.druid.indexing.common.config.TaskStorageConfig;
import org.apache.druid.indexing.common.task.Task;
import org.apache.druid.java.util.common.DateTimes;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.lifecycle.LifecycleStart;
import org.apache.druid.java.util.common.lifecycle.LifecycleStop;
import org.apache.druid.java.util.emitter.EmittingLogger;
import org.apache.druid.metadata.MetadataStorageActionHandler;
import org.apache.druid.metadata.MetadataStorageActionHandlerFactory;
import org.apache.druid.metadata.MetadataStorageActionHandlerTypes;
import org.apache.druid.metadata.MetadataStorageConnector;
import org.apache.druid.metadata.MetadataStorageTablesConfig;
import org.apache.druid.metadata.TaskLookup;
import org.apache.druid.metadata.TaskLookup.ActiveTaskLookup;
import org.apache.druid.metadata.TaskLookup.TaskLookupType;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;

public class MetadataTaskStorage implements TaskStorage
{

  private static final MetadataStorageActionHandlerTypes<Task, TaskStatus, TaskAction, TaskLock> TASK_TYPES = new MetadataStorageActionHandlerTypes<>()
  {
    @Override
    public TypeReference<Task> getEntryType()
    {
      return new TypeReference<>() {};
    }

    @Override
    public TypeReference<TaskStatus> getStatusType()
    {
      return new TypeReference<>() {};
    }

    @Override
    public TypeReference<TaskLock> getLockType()
    {
      return new TypeReference<>() {};
    }
  };

  private final MetadataStorageConnector metadataStorageConnector;
  private final TaskStorageConfig config;
  private final MetadataStorageActionHandler<Task, TaskStatus, TaskAction, TaskLock> handler;

  private static final EmittingLogger log = new EmittingLogger(MetadataTaskStorage.class);

  @Inject
  public MetadataTaskStorage(
      final MetadataStorageConnector metadataStorageConnector,
      final TaskStorageConfig config,
      final MetadataStorageActionHandlerFactory factory
  )
  {
    this.metadataStorageConnector = metadataStorageConnector;
    this.config = config;
    this.handler = factory.create(MetadataStorageTablesConfig.TASK_ENTRY_TYPE, TASK_TYPES);
  }

  @LifecycleStart
  public void start()
  {
    metadataStorageConnector.createTaskTables();
    // begins migration of existing tasks to new schema
    handler.populateTaskTypeAndGroupIdAsync();
  }

  @LifecycleStop
  public void stop()
  {
    // do nothing
  }

  @Override
  public void insert(final Task task, final TaskStatus status)
  {
    Preconditions.checkNotNull(task, "task");
    Preconditions.checkNotNull(status, "status");
    Preconditions.checkArgument(
        task.getId().equals(status.getId()),
        "Task/Status ID mismatch[%s/%s]",
        task.getId(),
        status.getId()
    );

    log.info("Inserting task [%s] with status [%s].", task.getId(), status);

    try {
      handler.insert(
          task.getId(),
          DateTimes.nowUtc(),
          task.getDataSource(),
          task,
          status.isRunnable(),
          status,
          task.getType(),
          task.getGroupId()
      );
    }
    catch (DruidException e) {
      throw e;
    }
    catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void setStatus(final TaskStatus status)
  {
    Preconditions.checkNotNull(status, "status");

    final String taskId = status.getId();
    log.info("Updating status of task [%s] to [%s].", taskId, status);

    final boolean set = handler.setStatus(taskId, status.isRunnable(), status);
    if (!set) {
      throw new ISE("No active task for id [%s]", taskId);
    }
  }

  @Override
  public Optional<Task> getTask(final String taskId)
  {
    return handler.getEntry(taskId);
  }

  @Override
  public Optional<TaskStatus> getStatus(final String taskId)
  {
    return handler.getStatus(taskId);
  }

  @Nullable
  @Override
  public TaskInfo<Task, TaskStatus> getTaskInfo(String taskId)
  {
    return handler.getTaskInfo(taskId);
  }

  @Override
  public List<Task> getActiveTasks()
  {
    // filter out taskInfo with a null 'task' which should only happen in practice if we are missing a jackson module
    // and don't know what to do with the payload, so we won't be able to make use of it anyway
    return handler.getTaskInfos(Collections.singletonMap(TaskLookupType.ACTIVE, ActiveTaskLookup.getInstance()), null)
                  .stream()
                  .filter(taskInfo -> taskInfo.getStatus().isRunnable() && taskInfo.getTask() != null)
                  .map(TaskInfo::getTask)
                  .collect(Collectors.toList());
  }

  @Override
  public List<Task> getActiveTasksByDatasource(String datasource)
  {
    List<TaskInfo<Task, TaskStatus>> activeTaskInfos = handler.getTaskInfos(
        Collections.singletonMap(TaskLookupType.ACTIVE, ActiveTaskLookup.getInstance()),
        datasource
    );
    ImmutableList.Builder<Task> tasksBuilder = ImmutableList.builder();
    for (TaskInfo<Task, TaskStatus> taskInfo : activeTaskInfos) {
      if (taskInfo.getStatus().isRunnable() && taskInfo.getTask() != null) {
        tasksBuilder.add(taskInfo.getTask());
      }
    }
    return tasksBuilder.build();
  }

  @Override
  public List<TaskInfo<Task, TaskStatus>> getTaskInfos(
      Map<TaskLookupType, TaskLookup> taskLookups,
      @Nullable String datasource
  )
  {
    Map<TaskLookupType, TaskLookup> theTaskLookups =
        TaskStorageUtils.processTaskLookups(
            taskLookups,
            DateTimes.nowUtc().minus(config.getRecentlyFinishedThreshold())
        );
    return Collections.unmodifiableList(handler.getTaskInfos(theTaskLookups, datasource));
  }

  @Override
  public List<TaskStatusPlus> getTaskStatusPlusList(
      Map<TaskLookupType, TaskLookup> taskLookups,
      @Nullable String datasource
  )
  {
    Map<TaskLookupType, TaskLookup> processedTaskLookups =
        TaskStorageUtils.processTaskLookups(
            taskLookups,
            DateTimes.nowUtc().minus(config.getRecentlyFinishedThreshold())
        );

    return Collections.unmodifiableList(
        handler.getTaskStatusList(processedTaskLookups, datasource)
               .stream()
               .map(TaskStatusPlus::fromTaskIdentifierInfo)
               .collect(Collectors.toList())
    );
  }

  @Override
  public void addLock(final String taskid, final TaskLock taskLock)
  {
    Preconditions.checkNotNull(taskid, "taskid");
    Preconditions.checkNotNull(taskLock, "taskLock");

    log.info(
        "Adding lock on interval[%s] version[%s] for task [%s].",
        taskLock.getInterval(), taskLock.getVersion(), taskid
    );

    handler.addLock(taskid, taskLock);
  }

  @Override
  public void replaceLock(String taskid, TaskLock oldLock, TaskLock newLock)
  {
    Preconditions.checkNotNull(taskid, "taskid");
    Preconditions.checkNotNull(oldLock, "oldLock");
    Preconditions.checkNotNull(newLock, "newLock");

    log.info(
        "Replacing an existing lock[%s] with a new lock[%s] for task [%s].",
        oldLock, newLock, taskid
    );

    final Long oldLockId = handler.getLockId(taskid, oldLock);
    if (oldLockId == null) {
      throw new ISE("Cannot find lock[%s] for task [%s]", oldLock, taskid);
    }

    handler.replaceLock(taskid, oldLockId, newLock);
  }

  @Override
  public void removeLock(String taskid, TaskLock taskLockToRemove)
  {
    Preconditions.checkNotNull(taskid, "taskid");
    Preconditions.checkNotNull(taskLockToRemove, "taskLockToRemove");

    final Long lockId = handler.getLockId(taskid, taskLockToRemove);
    if (lockId == null) {
      log.warn("Cannot find lock[%s]", taskLockToRemove);
    } else {
      log.info("Deleting TaskLock with id[%d]: %s", lockId, taskLockToRemove);
      handler.removeLock(lockId);
    }
  }

  @Override
  public void removeTasksOlderThan(long timestamp)
  {
    handler.removeTasksOlderThan(timestamp);
  }

  @Override
  public List<TaskLock> getLocks(String taskid)
  {
    return ImmutableList.copyOf(
        Iterables.transform(
            getLocksWithIds(taskid).entrySet(),
            Entry::getValue
        )
    );
  }

  private Map<Long, TaskLock> getLocksWithIds(final String taskid)
  {
    return handler.getLocks(taskid);
  }
}
