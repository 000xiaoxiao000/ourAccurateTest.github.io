package com.oAT.ai.agent.parallel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * 并行工具执行器
 * 支持并行执行多个独立的工具调用，提升响应速度
 */
public class ParallelToolExecutor {

    private static final Logger logger = LoggerFactory.getLogger(ParallelToolExecutor.class);
    
    private static final ParallelToolExecutor INSTANCE = new ParallelToolExecutor();
    
    /** 线程池：核心线程数4，最大线程数8 */
    private final ExecutorService executorService = new ThreadPoolExecutor(
            4, 8,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100),
            new ThreadFactory() {
                private int counter = 0;
                @Override
                public Thread newThread(Runnable r) {
                    Thread thread = new Thread(r);
                    thread.setName("parallel-tool-executor-" + counter++);
                    thread.setDaemon(true);
                    return thread;
                }
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    private ParallelToolExecutor() {
    }

    public static ParallelToolExecutor getInstance() {
        return INSTANCE;
    }

    /**
     * 并行执行多个任务
     * 
     * @param tasks 任务列表
     * @param timeout 超时时间（秒）
     * @return 执行结果列表
     */
    public List<ToolExecutionResult> executeParallel(List<ToolTask> tasks, long timeout) {
        List<CompletableFuture<ToolExecutionResult>> futures = new ArrayList<>();
        
        // 提交所有任务
        for (ToolTask task : tasks) {
            CompletableFuture<ToolExecutionResult> future = CompletableFuture
                .supplyAsync(() -> executeTask(task), executorService)
                .exceptionally(ex -> {
                    logger.error("Task execution failed: {}", task.getTaskName(), ex);
                    return new ToolExecutionResult(
                        task.getTaskName(),
                        null,
                        false,
                        "执行失败：" + ex.getMessage()
                    );
                });
            futures.add(future);
        }
        
        // 等待所有任务完成
        CompletableFuture<Void> allFutures = CompletableFuture.allOf(
            futures.toArray(new CompletableFuture[0])
        );
        
        try {
            allFutures.get(timeout, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            logger.warn("Parallel execution timeout after {} seconds", timeout);
            // 取消未完成的任务
            futures.forEach(f -> f.cancel(true));
        } catch (Exception e) {
            logger.error("Parallel execution failed", e);
        }
        
        // 收集结果
        List<ToolExecutionResult> results = new ArrayList<>();
        for (CompletableFuture<ToolExecutionResult> future : futures) {
            if (!future.isCancelled()) {
                try {
                    results.add(future.get());
                } catch (Exception e) {
                    logger.error("Failed to get task result", e);
                }
            }
        }
        
        logger.info("Parallel execution completed: {} tasks, {} succeeded", 
            tasks.size(), results.stream().filter(ToolExecutionResult::isSuccess).count());
        
        return results;
    }

    /**
     * 执行单个任务
     */
    private ToolExecutionResult executeTask(ToolTask task) {
        long startTime = System.currentTimeMillis();
        logger.debug("Executing task: {}", task.getTaskName());
        
        try {
            Object result = task.getSupplier().get();
            long duration = System.currentTimeMillis() - startTime;
            
            logger.debug("Task {} completed in {}ms", task.getTaskName(), duration);
            return new ToolExecutionResult(task.getTaskName(), result, true, null, duration);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            logger.error("Task {} failed after {}ms: {}", task.getTaskName(), duration, e.getMessage());
            return new ToolExecutionResult(task.getTaskName(), null, false, e.getMessage(), duration);
        }
    }

    /**
     * 关闭执行器
     */
    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.info("ParallelToolExecutor shutdown");
    }

    /**
     * 工具任务
     */
    public static class ToolTask {
        private final String taskName;
        private final Supplier<Object> supplier;

        public ToolTask(String taskName, Supplier<Object> supplier) {
            this.taskName = taskName;
            this.supplier = supplier;
        }

        public String getTaskName() {
            return taskName;
        }

        public Supplier<Object> getSupplier() {
            return supplier;
        }
    }

    /**
     * 工具执行结果
     */
    public static class ToolExecutionResult {
        private final String taskName;
        private final Object result;
        private final boolean success;
        private final String errorMessage;
        private final long duration;

        public ToolExecutionResult(String taskName, Object result, boolean success, String errorMessage) {
            this(taskName, result, success, errorMessage, 0);
        }

        public ToolExecutionResult(String taskName, Object result, boolean success, 
                                  String errorMessage, long duration) {
            this.taskName = taskName;
            this.result = result;
            this.success = success;
            this.errorMessage = errorMessage;
            this.duration = duration;
        }

        public String getTaskName() {
            return taskName;
        }

        public Object getResult() {
            return result;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public long getDuration() {
            return duration;
        }

        @Override
        public String toString() {
            return "ToolExecutionResult{" +
                    "taskName='" + taskName + '\'' +
                    ", success=" + success +
                    ", duration=" + duration + "ms" +
                    (errorMessage != null ? ", errorMessage='" + errorMessage + '\'' : "") +
                    '}';
        }
    }
}
