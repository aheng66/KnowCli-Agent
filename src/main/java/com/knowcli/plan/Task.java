package com.knowcli.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 单个任务类
 */
public class Task {
    private final String id;
    private final String description;
    private final TaskType type;
    private TaskStatus status;
    private String result;
    private String error;
    private final List<String> dependencies;
    private final List<String> dependents;
    private long startTime;
    private long endTime;

    public enum TaskStatus {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
        SKIPPED
    }

    public enum TaskType {
        PLANNING,
        FILE_READ,
        FILE_WRITE,
        COMMAND,
        ANALYSIS,
        VERIFICATION
    }

    public Task(String id, String description, TaskType type, List<String> dependencies) {
        this.id = requireNonBlank(id, "id");
        this.description = requireNonBlank(description, "description");
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.dependencies = new ArrayList<>(dependencies == null ? List.of() : dependencies);
        this.dependents = new ArrayList<>();
        this.status = TaskStatus.PENDING;
    }

    public String getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }

    public TaskType getType() {
        return type;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public String getResult() {
        return result;
    }

    public String getError() {
        return error;
    }

    public List<String> getDependencies() {
        return List.copyOf(dependencies);
    }

    public List<String> getDependents() {
        return List.copyOf(dependents);
    }

    public long getStartTime() {
        return startTime;
    }

    public long getEndTime() {
        return endTime;
    }

    //添加依赖
    public void addDependent(String taskId) {
        String dependent = requireNonBlank(taskId, "taskId");
        if (!dependents.contains(dependent)) {
            dependents.add(dependent);
        }
    }

    void clearDependents() {
        dependents.clear();
    }

    //标记任务开始
    public void markStarted() {
        this.status = TaskStatus.RUNNING;
        this.startTime = System.currentTimeMillis();
    }

    //标记任务结束
    public void markCompleted(String result) {
        this.status = TaskStatus.COMPLETED;
        this.result = result;
        this.error = null;
        this.endTime = System.currentTimeMillis();
    }

    //标记任务失败
    public void markFailed(String error) {
        this.status = TaskStatus.FAILED;
        this.error = error;
        this.endTime = System.currentTimeMillis();
    }

    //标记任务跳过
    public void markSkipped(String reason) {
        this.status = TaskStatus.SKIPPED;
        this.error = reason;
        this.endTime = System.currentTimeMillis();
    }

    //判断任务是否可执行
    public boolean isExecutable(Map<String, Task> allTasks) {
        if (status != TaskStatus.PENDING) {
            return false;
        }
        for (String depId : dependencies) {
            Task dep = allTasks.get(depId);
            if (dep == null || dep.getStatus() != TaskStatus.COMPLETED) {
                return false;
            }
        }
        return true;
    }

    //校验字符串参数不能为空或空白
    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value.strip();
    }
}