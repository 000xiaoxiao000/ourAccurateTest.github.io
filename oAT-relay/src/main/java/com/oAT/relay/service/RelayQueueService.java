package com.oAT.relay.service;

import com.oAT.relay.config.RelayProperties;
import com.oAT.relay.model.RelayRequest;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

@Service
public class RelayQueueService {
    private static final Logger logger = LoggerFactory.getLogger(RelayQueueService.class);

    private final RelayProperties properties;
    private final RelayForwardClient forwardClient;
    private final RelayMetrics metrics;
    private final BlockingQueue<RelayRequest> queue;
    private final List<Thread> workers = new ArrayList<>();
    private volatile boolean running = true;

    public RelayQueueService(RelayProperties properties, RelayForwardClient forwardClient, RelayMetrics metrics) {
        this.properties = properties;
        this.forwardClient = forwardClient;
        this.metrics = metrics;
        int capacity = Math.max(1, properties.getQueue().getCapacity());
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    @PostConstruct
    public void startWorkers() {
        if (!properties.getQueue().isEnabled()) {
            logger.info("relay async queue disabled");
            return;
        }
        int workerCount = Math.max(1, properties.getQueue().getWorkers());
        for (int i = 0; i < workerCount; i++) {
            Thread worker = new Thread(this::runWorker, "oat-relay-worker-" + i);
            worker.setDaemon(true);
            worker.start();
            workers.add(worker);
        }
        logger.info("relay async queue started, capacity={}, workers={}", properties.getQueue().getCapacity(), workerCount);
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        for (Thread worker : workers) {
            worker.interrupt();
        }
    }

    public boolean offer(RelayRequest request) throws InterruptedException {
        boolean offered = queue.offer(request, properties.getQueue().getOfferTimeoutMs(), TimeUnit.MILLISECONDS);
        if (offered) {
            metrics.markEnqueueSuccess();
        } else {
            metrics.markEnqueueFailure();
        }
        return offered;
    }

    public int depth() {
        return queue.size();
    }

    private void runWorker() {
        while (running || !queue.isEmpty()) {
            try {
                RelayRequest request = queue.poll(500, TimeUnit.MILLISECONDS);
                if (request == null) {
                    continue;
                }
                forwardWithRetry(request);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                if (!running) {
                    return;
                }
            } catch (Throwable ex) {
                logger.warn("relay worker unexpected error: {}", ex.getMessage(), ex);
            }
        }
    }

    private void forwardWithRetry(RelayRequest request) throws InterruptedException {
        int maxAttempts = Math.max(1, properties.getRetry().getMaxAttempts());
        while (request.getAttempts() < maxAttempts) {
            int attempt = request.nextAttempt();
            try {
                ResponseEntity<String> response = forwardClient.forward(request);
                if (response.getStatusCode().is2xxSuccessful()) {
                    return;
                }
                logger.warn("relay forward non-2xx, requestId={}, path={}, status={}, attempt={}",
                        request.getRequestId(), request.getPath(), response.getStatusCode().value(), attempt);
            } catch (Throwable ex) {
                logger.warn("relay retryable forward error, requestId={}, path={}, attempt={}, error={}",
                        request.getRequestId(), request.getPath(), attempt, ex.getMessage());
            }
            if (request.getAttempts() < maxAttempts) {
                TimeUnit.MILLISECONDS.sleep(nextBackoffMs(attempt));
            }
        }
        metrics.markDeadLetter();
        logger.error("relay request moved to dead letter, requestId={}, path={}, attempts={}",
                request.getRequestId(), request.getPath(), request.getAttempts());
    }

    private long nextBackoffMs(int attempt) {
        long initial = Math.max(1, properties.getRetry().getInitialBackoffMs());
        long max = Math.max(initial, properties.getRetry().getMaxBackoffMs());
        long value = initial * (1L << Math.min(attempt - 1, 20));
        return Math.min(value, max);
    }
}
