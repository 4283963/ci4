package com.coldchain.collector.service;

import com.coldchain.collector.mapper.TemperatureMapper;
import com.coldchain.common.entity.TemperatureRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class ClickHouseWriterService {

    @Value("${coldchain.clickhouse.batch-size:2000}")
    private int batchSize;

    @Resource
    private TemperatureMapper temperatureMapper;

    private final ConcurrentLinkedQueue<TemperatureRecord> bufferQueue = new ConcurrentLinkedQueue<>();

    private final AtomicLong totalWritten = new AtomicLong(0);
    private final AtomicLong totalFailed = new AtomicLong(0);

    @PostConstruct
    public void init() {
        log.info("[CKWriter] ClickHouse批量写入服务已初始化, 批次大小: {}", batchSize);
    }

    public void addRecord(TemperatureRecord record) {
        bufferQueue.offer(record);
        if (bufferQueue.size() >= batchSize) {
            flush();
        }
    }

    public void addRecords(List<TemperatureRecord> records) {
        bufferQueue.addAll(records);
        if (bufferQueue.size() >= batchSize) {
            flush();
        }
    }

    @Scheduled(fixedDelayString = "${coldchain.clickhouse.flush-interval-seconds:5}000")
    public void scheduledFlush() {
        if (!bufferQueue.isEmpty()) {
            flush();
        }
    }

    public synchronized void flush() {
        if (bufferQueue.isEmpty()) {
            return;
        }
        List<TemperatureRecord> batch = new ArrayList<>(batchSize);
        while (!bufferQueue.isEmpty() && batch.size() < batchSize) {
            TemperatureRecord record = bufferQueue.poll();
            if (record != null) {
                batch.add(record);
            }
        }
        if (!batch.isEmpty()) {
            doWrite(batch);
        }
    }

    private void doWrite(List<TemperatureRecord> batch) {
        int size = batch.size();
        long start = System.currentTimeMillis();
        try {
            temperatureMapper.batchInsert(batch);
            long cost = System.currentTimeMillis() - start;
            long total = totalWritten.addAndGet(size);
            if (log.isInfoEnabled()) {
                log.info("[CKWriter] 写入ClickHouse成功, 本批次:{}, 累计:{}, 耗时:{}ms, QPS:{:.0f}",
                        size, total, cost, (cost > 0 ? size * 1000.0 / cost : size * 1000.0));
            }
        } catch (Exception e) {
            totalFailed.addAndGet(size);
            log.error("[CKWriter] 写入ClickHouse失败, 本批次:{}, 原因:{}", size, e.getMessage(), e);
            bufferQueue.addAll(batch);
        }
    }

    @PreDestroy
    public void destroy() {
        log.info("[CKWriter] 应用关闭, 开始刷写剩余数据...");
        flush();
        log.info("[CKWriter] 最终统计 - 成功写入:{}, 失败:{}", totalWritten.get(), totalFailed.get());
    }
}
