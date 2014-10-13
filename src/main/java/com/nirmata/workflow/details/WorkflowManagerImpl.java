package com.nirmata.workflow.details;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.nirmata.workflow.WorkflowManager;
import com.nirmata.workflow.admin.RunInfo;
import com.nirmata.workflow.admin.TaskInfo;
import com.nirmata.workflow.admin.WorkflowAdmin;
import com.nirmata.workflow.events.WorkflowListenerManager;
import com.nirmata.workflow.details.internalmodels.RunnableTask;
import com.nirmata.workflow.details.internalmodels.StartedTask;
import com.nirmata.workflow.executor.TaskExecution;
import com.nirmata.workflow.executor.TaskExecutor;
import com.nirmata.workflow.models.ExecutableTask;
import com.nirmata.workflow.models.RunId;
import com.nirmata.workflow.models.Task;
import com.nirmata.workflow.models.TaskExecutionResult;
import com.nirmata.workflow.models.TaskId;
import com.nirmata.workflow.models.TaskType;
import com.nirmata.workflow.queue.QueueConsumer;
import com.nirmata.workflow.queue.QueueFactory;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.utils.CloseableUtils;
import org.apache.curator.utils.ZKPaths;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class WorkflowManagerImpl implements WorkflowManager, WorkflowAdmin
{
    private final Logger log = LoggerFactory.getLogger(getClass());
    private final CuratorFramework curator;
    private final String instanceName;
    private final List<QueueConsumer> consumers;
    private final SchedulerSelector schedulerSelector;
    private final AtomicReference<State> state = new AtomicReference<>(State.LATENT);

    private static final TaskType nullTaskType = new TaskType("", "", false);

    private enum State
    {
        LATENT,
        STARTED,
        CLOSED
    }

    public WorkflowManagerImpl(CuratorFramework curator, QueueFactory queueFactory, String instanceName, List<TaskExecutorSpec> specs)
    {
        this.curator = Preconditions.checkNotNull(curator, "curator cannot be null");
        queueFactory = Preconditions.checkNotNull(queueFactory, "queueFactory cannot be null");
        this.instanceName = Preconditions.checkNotNull(instanceName, "instanceName cannot be null");
        specs = Preconditions.checkNotNull(specs, "specs cannot be null");

        consumers = makeTaskConsumers(queueFactory, specs);
        schedulerSelector = new SchedulerSelector(this, queueFactory, specs);
    }

    public CuratorFramework getCurator()
    {
        return curator;
    }

    @Override
    public void start()
    {
        Preconditions.checkState(state.compareAndSet(State.LATENT, State.STARTED), "Already started");

        consumers.forEach(QueueConsumer::start);
        schedulerSelector.start();
    }

    @Override
    public WorkflowListenerManager newWorkflowListenerManager()
    {
        return new WorkflowListenerManagerImpl(this);
    }

    @Override
    public RunId submitTask(Task task)
    {
        return submitSubTask(null, task);
    }

    @Override
    public RunId submitSubTask(RunId parentRunId, Task task)
    {
        Preconditions.checkState(state.get() == State.STARTED, "Not started");

        RunId runId = new RunId();
        RunnableTaskDagBuilder builder = new RunnableTaskDagBuilder(task);
        Map<TaskId, ExecutableTask> tasks = builder
            .getTasks()
            .values()
            .stream()
            .collect(Collectors.toMap(Task::getTaskId, t -> new ExecutableTask(runId, t.getTaskId(), t.isExecutable() ? t.getTaskType() : nullTaskType, t.getMetaData(), t.isExecutable())));
        RunnableTask runnableTask = new RunnableTask(tasks, builder.getEntries(), LocalDateTime.now(), null, parentRunId);

        try
        {
            byte[] runnableTaskJson = JsonSerializer.toBytes(JsonSerializer.newRunnableTask(runnableTask));
            String runPath = ZooKeeperConstants.getRunPath(runId);
            curator.create().creatingParentsIfNeeded().forPath(runPath, runnableTaskJson);
        }
        catch ( Exception e )
        {
            throw new RuntimeException(e);
        }

        return runId;
    }

    @Override
    public boolean cancelRun(RunId runId)
    {
        log.info("Attempting to cancel run " + runId);

        String runPath = ZooKeeperConstants.getRunPath(runId);
        try
        {
            Stat stat = new Stat();
            byte[] json = curator.getData().storingStatIn(stat).forPath(runPath);
            RunnableTask runnableTask = JsonSerializer.getRunnableTask(JsonSerializer.fromBytes(json));
            Scheduler.completeRunnableTask(log, this, runId, runnableTask, stat.getVersion());
            return true;
        }
        catch ( KeeperException.NoNodeException ignore )
        {
            return false;
        }
        catch ( Exception e )
        {
            throw new RuntimeException("Could not cancel runId " + runId, e);
        }
    }

    @Override
    public Optional<TaskExecutionResult> getTaskExecutionResult(RunId runId, TaskId taskId)
    {
        String completedTaskPath = ZooKeeperConstants.getCompletedTaskPath(runId, taskId);
        try
        {
            byte[] json = curator.getData().forPath(completedTaskPath);
            TaskExecutionResult taskExecutionResult = JsonSerializer.getTaskExecutionResult(JsonSerializer.fromBytes(json));
            return Optional.of(taskExecutionResult);
        }
        catch ( KeeperException.NoNodeException dummy )
        {
            // dummy
        }
        catch ( Exception e )
        {
            throw new RuntimeException(String.format("No data for runId %s taskId %s", runId, taskId), e);
        }
        return Optional.empty();
    }

    public String getInstanceName()
    {
        return instanceName;
    }

    @Override
    public void close() throws IOException
    {
        if ( state.compareAndSet(State.STARTED, State.CLOSED) )
        {
            CloseableUtils.closeQuietly(schedulerSelector);
            consumers.forEach(CloseableUtils::closeQuietly);
        }
    }

    @Override
    public WorkflowAdmin getAdmin()
    {
        return this;
    }

    @Override
    public boolean clean(RunId runId)
    {
        String runPath = ZooKeeperConstants.getRunPath(runId);
        try
        {
            byte[] json = curator.getData().forPath(runPath);
            RunnableTask runnableTask = JsonSerializer.getRunnableTask(JsonSerializer.fromBytes(json));
            runnableTask.getTasks().keySet().forEach(taskId -> {
                String startedTaskPath = ZooKeeperConstants.getStartedTaskPath(runId, taskId);
                try
                {
                    curator.delete().forPath(startedTaskPath);
                }
                catch ( KeeperException.NoNodeException ignore )
                {
                    // ignore
                }
                catch ( Exception e )
                {
                    throw new RuntimeException("Could not delete started task at: " + startedTaskPath, e);
                }

                String completedTaskPath = ZooKeeperConstants.getCompletedTaskPath(runId, taskId);
                try
                {
                    curator.delete().forPath(completedTaskPath);
                }
                catch ( KeeperException.NoNodeException ignore )
                {
                    // ignore
                }
                catch ( Exception e )
                {
                    throw new RuntimeException("Could not delete completed task at: " + completedTaskPath, e);
                }
            });

            try
            {
                curator.delete().forPath(runPath);
            }
            catch ( Exception e )
            {
                // at this point, the node should exist
                throw new RuntimeException(e);
            }

            return true;
        }
        catch ( KeeperException.NoNodeException dummy )
        {
            return false;
        }
        catch ( Throwable e )
        {
            throw new RuntimeException(e);
        }
    }

    @Override
    public RunInfo getRunInfo(RunId runId)
    {
        try
        {
            String runPath = ZooKeeperConstants.getRunPath(runId);
            byte[] json = curator.getData().forPath(runPath);
            RunnableTask runnableTask = JsonSerializer.getRunnableTask(JsonSerializer.fromBytes(json));
            return new RunInfo(runId, runnableTask.getStartTimeUtc(), runnableTask.getCompletionTimeUtc().orElse(null));
        }
        catch ( Exception e )
        {
            throw new RuntimeException("Could not read run: " + runId, e);
        }
    }

    @Override
    public List<RunInfo> getRunInfo()
    {
        try
        {
            String runParentPath = ZooKeeperConstants.getRunParentPath();
            return curator.getChildren().forPath(runParentPath).stream()
                .map(child -> {
                    String fullPath = ZKPaths.makePath(runParentPath, child);
                    try
                    {
                        RunId runId = new RunId(ZooKeeperConstants.getRunIdFromRunPath(fullPath));
                        byte[] json = curator.getData().forPath(fullPath);
                        RunnableTask runnableTask = JsonSerializer.getRunnableTask(JsonSerializer.fromBytes(json));
                        return new RunInfo(runId, runnableTask.getStartTimeUtc(), runnableTask.getCompletionTimeUtc().orElse(null));
                    }
                    catch ( KeeperException.NoNodeException ignore )
                    {
                        // ignore - must have been deleted in the interim
                    }
                    catch ( Exception e )
                    {
                        throw new RuntimeException("Trying to read run info from: " + fullPath, e);
                    }
                    return null;
                })
                .filter(info -> (info != null))
                .collect(Collectors.toList());
        }
        catch ( Throwable e )
        {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<TaskInfo> getTaskInfo(RunId runId)
    {
        List<TaskInfo> taskInfos = Lists.newArrayList();
        String startedTasksParentPath = ZooKeeperConstants.getStartedTasksParentPath();
        String completedTaskParentPath = ZooKeeperConstants.getCompletedTaskParentPath();
        try
        {
            String runPath = ZooKeeperConstants.getRunPath(runId);
            byte[] runJson = curator.getData().forPath(runPath);
            RunnableTask runnableTask = JsonSerializer.getRunnableTask(JsonSerializer.fromBytes(runJson));

            Set<TaskId> notStartedTasks = runnableTask.getTasks().values().stream().filter(ExecutableTask::isExecutable).map(ExecutableTask::getTaskId).collect(Collectors.toSet());
            Map<TaskId, StartedTask> startedTasks = Maps.newHashMap();

            curator.getChildren().forPath(startedTasksParentPath).stream().forEach(child -> {
                String fullPath = ZKPaths.makePath(startedTasksParentPath, child);
                TaskId taskId = new TaskId(ZooKeeperConstants.getTaskIdFromStartedTasksPath(fullPath));
                try
                {
                    byte[] json = curator.getData().forPath(fullPath);
                    StartedTask startedTask = JsonSerializer.getStartedTask(JsonSerializer.fromBytes(json));
                    startedTasks.put(taskId, startedTask);
                    notStartedTasks.remove(taskId);
                }
                catch ( KeeperException.NoNodeException ignore )
                {
                    // ignore - must have been deleted in the interim
                }
                catch ( Exception e )
                {
                    throw new RuntimeException("Trying to read started task info from: " + fullPath, e);
                }
            });

            curator.getChildren().forPath(completedTaskParentPath).stream().forEach(child -> {
                String fullPath = ZKPaths.makePath(completedTaskParentPath, child);
                TaskId taskId = new TaskId(ZooKeeperConstants.getTaskIdFromCompletedTasksPath(fullPath));
                StartedTask startedTask = startedTasks.remove(taskId);
                if ( startedTask != null )  // otherwise it must have been deleted
                {
                    try
                    {
                        byte[] json = curator.getData().forPath(fullPath);
                        TaskExecutionResult taskExecutionResult = JsonSerializer.getTaskExecutionResult(JsonSerializer.fromBytes(json));
                        taskInfos.add(new TaskInfo(taskId, startedTask.getInstanceName(), startedTask.getStartDateUtc(), taskExecutionResult));
                        notStartedTasks.remove(taskId);
                    }
                    catch ( KeeperException.NoNodeException ignore )
                    {
                        // ignore - must have been deleted in the interim
                    }
                    catch ( Exception e )
                    {
                        throw new RuntimeException("Trying to read completed task info from: " + fullPath, e);
                    }
                }
            });

            // remaining started tasks have not completed
            startedTasks.entrySet().forEach(entry -> {
                StartedTask startedTask = entry.getValue();
                taskInfos.add(new TaskInfo(entry.getKey(), startedTask.getInstanceName(), startedTask.getStartDateUtc()));
            });

            // finally, taskIds not added have not started
            notStartedTasks.forEach(taskId -> taskInfos.add(new TaskInfo(taskId)));
        }
        catch ( Throwable e )
        {
            throw new RuntimeException(e);
        }
        return taskInfos;
    }

    @VisibleForTesting
    SchedulerSelector getSchedulerSelector()
    {
        return schedulerSelector;
    }

    private void executeTask(TaskExecutor taskExecutor, ExecutableTask executableTask)
    {
        if ( state.get() != State.STARTED )
        {
            return;
        }

        log.info("Executing task: " + executableTask);
        TaskExecution taskExecution = taskExecutor.newTaskExecution(this, executableTask);

        TaskExecutionResult result = taskExecution.execute();
        if ( result == null )
        {
            throw new RuntimeException(String.format("null returned from task executor for run: %s, task %s", executableTask.getRunId(), executableTask.getTaskId()));
        }
        String json = JsonSerializer.nodeToString(JsonSerializer.newTaskExecutionResult(result));
        try
        {
            String path = ZooKeeperConstants.getCompletedTaskPath(executableTask.getRunId(), executableTask.getTaskId());
            curator.create().creatingParentsIfNeeded().forPath(path, json.getBytes());
        }
        catch ( Exception e )
        {
            log.error("Could not set completed data for executable task: " + executableTask, e);
            throw new RuntimeException(e);
        }
    }

    private List<QueueConsumer> makeTaskConsumers(QueueFactory queueFactory, List<TaskExecutorSpec> specs)
    {
        ImmutableList.Builder<QueueConsumer> builder = ImmutableList.builder();
        specs.forEach(spec -> IntStream.range(0, spec.getQty()).forEach(i -> {
            QueueConsumer consumer = queueFactory.createQueueConsumer(this, t -> executeTask(spec.getTaskExecutor(), t), spec.getTaskType());
            builder.add(consumer);
        }));

        return builder.build();
    }
}