package com.coldchain.collector.service;

import com.coldchain.collector.mapper.TemperatureMapper;
import com.coldchain.common.entity.TemperatureRecord;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class ClickHouseWriterService {

    @Value("${coldchain.clickhouse.batch-size:10000}")
    private int batchSize;

    @Value("${coldchain.clickhouse.queue-capacity:200000}")
    private int queueCapacity;

    @Value("${coldchain.clickhouse.min-flush-interval-ms:1000}")
    private long minFlushIntervalMs;

    @Value("${coldchain.clickhouse.max-flush-interval-ms:5000}")
    private long maxFlushIntervalMs;

    @Value("${coldchain.clickhouse.back-pressure-threshold:0.8}")
    private double backPressureThreshold;

    @Resource
    private TemperatureMapper temperatureMapper;

    private BlockingQueue<TemperatureRecord> bufferQueue;

    private final AtomicLong totalWritten = new AtomicLong(0);
    private final AtomicLong totalFailed = new AtomicLong(0);
    private final AtomicLong totalDropped = new AtomicLong(0);
    private final AtomicLong flushCount = new AtomicLong(0);
    private final AtomicLong consecutiveFailures = new AtomicLong(0);

    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile long lastFlushTime = 0L;

    private static final long MAX_BACKOFF_MS = 30_000L;
    private static final long BASE_BACKOFF_MS = 100L;

    @Getter
    private volatile String lastErrorMessage = "";

    @PostConstruct
    public void init() {
        this.bufferQueue = new ArrayBlockingQueue<>(queueCapacity);
        this.running.set(true);

        Thread writerThread = new Thread(this::writeLoop, "ck-writer-main");
        writerThread.setDaemon(true);
        writerThread.start();

        log.info("[CKWriter] ClickHouse批量写入服务已启动 | 批次大小:{} | 队列容量:{} | 最小刷写间隔:{}ms | 最大刷写间隔:{}ms",
                batchSize, queueCapacity, minFlushIntervalMs, maxFlushIntervalMs);
    }

    public boolean addRecord(TemperatureRecord record) {
        if (record == null) {
            return false;
        }
        boolean offered = bufferQueue.offer(record);
        if (!offered) {
            totalDropped.incrementAndGet();
            if (totalDropped.get() % 1000 == 0) {
                log.warn("[CKWriter] 队列已满, 数据被丢弃! 已丢弃总数:{}", totalDropped.get());
            }
        }
        return offered;
    }

    public boolean addRecords(List<TemperatureRecord> records) {
        if (records == null || records.isEmpty()) {
            return true;
        }
        int dropped = 0;
        for (TemperatureRecord record : records) {
            if (!bufferQueue.offer(record)) {
                dropped++;
            }
        }
        if (dropped > 0) {
            totalDropped.addAndGet(dropped);
            log.warn("[CKWriter] 队列已满, 批量丢弃{}条数据, 累计丢弃:{}", dropped, totalDropped.get());
            return false;
        }
        return true;
    }

    public boolean isUnderBackPressure() {
        return bufferQueue.size() > (int) (queueCapacity * backPressureThreshold);
    }

    public int getQueueSize() {
        return bufferQueue.size();
    }

    public long getTotalWritten() {
        return totalWritten.get();
    }

    public long getTotalFailed() {
        return totalFailed.get();
    }

    public long getTotalDropped() {
        return totalDropped.get();
    }

    public long getFlushCount() {
        return flushCount.get();
    }

    public double getQueueUsage() {
        return queueCapacity > 0 ? (double) bufferQueue.size() / queueCapacity : 0.0;
    }

    private void writeLoop() {
        log.info("[CKWriter] 写入主循环已启动");
        List<TemperatureRecord> batch = new ArrayList<>(batchSize);

        while (running.get()) {
            try {
                batch.clear();

                int available = bufferQueue.drainTo(batch, batchSize);
                if (available == 0) {
                    TemperatureRecord first = bufferQueue.poll(minFlushIntervalMs, TimeUnit.MILLISECONDS);
                    if (first != null) {
                        batch.add(first);
                        bufferQueue.drainTo(batch, batchSize - 1);
                    }
                }

                if (batch.isEmpty()) {
                    continue;
                }

                long now = System.currentTimeMillis();
                long timeSinceLastFlush = now - lastFlushTime;

                if (batch.size() < batchSize && timeSinceLastFlush < minFlushIntervalMs && bufferQueue.size() < batchSize) {
                    Thread.sleep(Math.min(minFlushIntervalMs - timeSinceLastFlush, 100L));
                    bufferQueue.drainTo(batch, batchSize - batch.size());
                }

                boolean forceFlush = (System.currentTimeMillis() - lastFlushTime) >= maxFlushIntervalMs;
                if (batch.size() < batchSize / 2 && !forceFlush) {
                    Thread.sleep(100);
                    continue;
                }

                flushBatch(batch);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("[CKWriter] 写入线程被中断");
                break;
            } catch (Exception e) {
                log.error("[CKWriter] 写入主循环异常: {}", e.getMessage(), e);
                sleepQuietly(1000);
            }
        }

        log.info("[CKWriter] 写入主循环已退出, 开始刷写剩余 {} 条数据...", bufferQueue.size());
        flushRemaining();
    }

    private void flushBatch(List<TemperatureRecord> batch) {
        int size = batch.size();
        long start = System.currentTimeMillis();
        long backoff = calculateBackoff();

        if (backoff > 0) {
            sleepQuietly(backoff);
        }

        try {
            sortBatch(batch);

            temperatureMapper.batchInsert(batch);

            lastFlushTime = System.currentTimeMillis();
            long cost = lastFlushTime - start;
            long total = totalWritten.addAndGet(size);
            consecutiveFailures.set(0);
            flushCount.incrementAndGet();
            lastErrorMessage = "";

            if (log.isInfoEnabled() && flushCount.get() % 10 == 0) {
                log.info("[CKWriter] 写入成功 | 批次:{}条 | 累计:{} | 耗时:{}ms | QPS:{:.0f} | 队列剩余:{} | 使用率:{:.1%}",
                        size, total, cost,
                        (cost > 0 ? size * 1000.0 / cost : size * 1000.0),
                        bufferQueue.size(), getQueueUsage());
            }

        } catch (Exception e) {
            long failCount = consecutiveFailures.incrementAndGet();
            totalFailed.addAndGet(size);
            lastErrorMessage = e.getMessage();

            log.error("[CKWriter] 写入失败 | 批次:{}条 | 连续失败:{}次 | 退避:{}ms | 原因:{} | 队列剩余:{}",
                    size, failCount, calculateBackoff(), e.getMessage(), bufferQueue.size());

            if (isTooManyPartsError(e)) {
                log.error("[CKWriter] 检测到 Too many parts 错误! ClickHouse 合并跟不上, 将增大退避时间并减少批次大小...");
            }

            if (failCount <= 5) {
                boolean requeued = requeueBatch(batch);
                if (!requeued) {
                    log.error("[CKWriter] 回写队列失败, 丢弃 {} 条数据", batch.size());
                    totalDropped.addAndGet(size);
                }
            } else {
                log.error("[CKWriter] 连续失败超过5次, 丢弃本批次 {} 条数据避免内存溢出", size);
                totalDropped.addAndGet(size);
            }
        }
    }

    private void sortBatch(List<TemperatureRecord> batch) {
        if (batch == null || batch.size() <= 1) {
            return;
        }
        batch.sort(Comparator
                .comparing(TemperatureRecord::getDeviceId)
                .thenComparing(TemperatureRecord::getReportTime)
                .thenComparing(TemperatureRecord::getId));
    }

    private boolean requeueBatch(List<TemperatureRecord> batch) {
        if (batch == null || batch.isEmpty()) {
            return true;
        }
        int requeueCount = 0;
        for (TemperatureRecord record : batch) {
            if (bufferQueue.offer(record)) {
                requeueCount++;
            }
        }
        if (requeueCount < batch.size()) {
            log.warn("[CKWriter] 回写队列空间不足, 仅回写 {}/{} 条", requeueCount, batch.size());
        }
        return requeueCount == batch.size();
    }

    private long calculateBackoff() {
        long failures = consecutiveFailures.get();
        if (failures == 0) {
            return 0L;
        }
        long backoff = BASE_BACKOFF_MS * (1L << Math.min(failures - 1, 10));
        return Math.min(backoff, MAX_BACKOFF_MS);
    }

    private boolean isTooManyPartsError(Exception e) {
        if (e == null || e.getMessage() == null) {
            return false;
        }
        String msg = e.getMessage().toLowerCase();
        return msg.contains("too many parts") || msg.contains("too many parts for") || msg.contains("merge");
    }

    private void flushRemaining() {
        List<TemperatureRecord> remaining = new ArrayList<>(bufferQueue.size());
        bufferQueue.drainTo(remaining);
        if (!remaining.isEmpty()) {
            log.info("[CKWriter] 刷写剩余 {} 条数据...", remaining.size());
            sortBatch(remaining);
            try {
                temperatureMapper.batchInsert(remaining);
                totalWritten.addAndGet(remaining.size());
                log.info("[CKWriter] 剩余数据刷写完成");
            } catch (Exception e) {
                log.error("[CKWriter] 剩余数据刷写失败: {}", e.getMessage());
            }
        }
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Scheduled(fixedDelay = 30_000)
    public void healthCheck() {
        long written = totalWritten.get();
        long failed = totalFailed.get();
        long dropped = totalDropped.get();
        int queueSize = bufferQueue.size();
        double usage = getQueueUsage();
        long consecutive = consecutiveFailures.get();

        log.info("[CKWriter] 健康状态 | 写入总量:{} | 失败总量:{} | 丢弃总量:{} | 刷写次数:{} | 队列:{}/{} ({:.1%}) | 连续失败:{}",
                written, failed, dropped, flushCount.get(),
                queueSize, queueCapacity, usage, consecutive);

        if (usage > backPressureThreshold) {
            log.warn("[CKWriter] ⚠️ 队列使用率超过 {}%, 进入背压状态!", (int) (backPressureThreshold * 100));
        }
        if (consecutive >= 3) {
            log.error("[CKWriter] ⚠️ 连续写入失败 {} 次, ClickHouse 可能异常!", consecutive);
        }
    }

    @PreDestroy
    public void destroy() {
        log.info("[CKWriter] 应用关闭, 停止写入服务...");
        running.set(false);
        sleepQuietly(2000);
        log.info("[CKWriter] 最终统计 - 成功写入:{} | 失败:{} | 丢弃:{}",
                totalWritten.get(), totalFailed.get(), totalDropped.get());
    }
}
