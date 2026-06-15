-- ============================================================
-- ClickHouse 初始化脚本 - 冷链监控数据库
-- 引擎: MergeTree (时序表最佳实践)
-- 适用版本: ClickHouse 22.x / 23.x / 24.x
-- 优化重点: 解决高并发写入下 Too many parts 问题
-- ============================================================

-- 1. 创建数据库
CREATE DATABASE IF NOT EXISTS cold_chain
    COMMENT '冷链监控业务数据库'
    ENGINE = Atomic;

-- 切换数据库
USE cold_chain;

-- ============================================================
-- 2. 温度记录表 (核心时序表)
-- 分区策略: 按月分区 (toYYYYMM)
-- 排序键: device_id + report_time + id
--        【关键】写入前必须按排序键排序！否则会产生大量小 part
-- TTL: 保留365天数据, 到期自动清理
-- ============================================================
CREATE TABLE IF NOT EXISTS cold_chain.temperature_record
(
    `id`              Int64                    COMMENT '雪花ID主键',
    `device_id`       LowCardinality(String)   COMMENT '设备ID(低基数优化)',
    `vehicle_no`      LowCardinality(String)   DEFAULT ''  COMMENT '车牌号(低基数优化)',
    `temperature`     Decimal32(2)             COMMENT '温度(℃)',
    `humidity`        Decimal32(2)             DEFAULT 0   COMMENT '湿度(%)',
    `longitude`       Decimal32(6)             DEFAULT 0   COMMENT '经度',
    `latitude`        Decimal32(6)             DEFAULT 0   COMMENT '纬度',
    `status`          Int8                     DEFAULT 1   COMMENT '状态:1-正常 0-异常',
    `report_time`     DateTime                 COMMENT '设备上报时间',
    `create_time`     DateTime                 DEFAULT now() COMMENT '入库时间'
)
ENGINE = MergeTree()
PARTITION BY toYYYYMM(report_time)
PRIMARY KEY (device_id, report_time)
ORDER BY (device_id, report_time, id)
TTL report_time + INTERVAL 365 DAY
    DELETE WHERE 1
SETTINGS
    index_granularity = 8192,
    min_bytes_for_wide_part = '10M',
    min_rows_for_wide_part = 100000,
    max_parts_in_total = 100000,
    storage_policy = 'default',
    merge_with_ttl_timeout = 14400;

-- 索引: 按车牌号查询 (二级跳数索引)
ALTER TABLE cold_chain.temperature_record
    ADD INDEX IF NOT EXISTS idx_vehicle_no vehicle_no TYPE set(1000) GRANULARITY 4;

-- 索引: 布隆过滤器 - 按设备ID快速定位
ALTER TABLE cold_chain.temperature_record
    ADD INDEX IF NOT EXISTS idx_device_id_bf device_id TYPE bloom_filter(0.01) GRANULARITY 1;

-- ============================================================
-- 3. 物化视图: 设备温度统计 - 每小时聚合
-- ============================================================
CREATE MATERIALIZED VIEW IF NOT EXISTS cold_chain.mv_device_temp_hourly
ENGINE = SummingMergeTree()
PARTITION BY toYYYYMM(hour_start)
PRIMARY KEY (device_id, hour_start)
ORDER BY (device_id, hour_start)
AS
SELECT
    device_id,
    toStartOfHour(report_time)           AS hour_start,
    count()                              AS record_count,
    min(temperature)                     AS min_temp,
    max(temperature)                     AS max_temp,
    avg(temperature)                     AS avg_temp,
    min(humidity)                        AS min_humidity,
    max(humidity)                        AS max_humidity,
    avg(humidity)                        AS avg_humidity
FROM cold_chain.temperature_record
GROUP BY device_id, toStartOfHour(report_time);

-- ============================================================
-- 4. 物化视图: 车辆温度统计 - 每日聚合
-- ============================================================
CREATE MATERIALIZED VIEW IF NOT EXISTS cold_chain.mv_vehicle_temp_daily
ENGINE = SummingMergeTree()
PARTITION BY toYYYYMM(day_start)
PRIMARY KEY (vehicle_no, day_start)
ORDER BY (vehicle_no, day_start)
AS
SELECT
    vehicle_no,
    toStartOfDay(report_time)            AS day_start,
    count()                              AS record_count,
    min(temperature)                     AS min_temp,
    max(temperature)                     AS max_temp,
    avg(temperature)                     AS avg_temp,
    countIf(temperature > 8)             AS high_temp_count,
    countIf(temperature < -18)           AS low_temp_count
FROM cold_chain.temperature_record
WHERE vehicle_no != ''
GROUP BY vehicle_no, toStartOfDay(report_time);

-- ============================================================
-- 5. 高并发写入最佳实践
-- ============================================================
-- 【应用端必须遵守】
-- 1. 大批次写入: 每批次 5000~50000 行, 严禁逐行插入
-- 2. 写入前排序: 按 ORDER BY 键 (device_id, report_time, id) 排序
-- 3. 控制写入频率: 每秒 1~2 次即可, 别超过 5 次
-- 4. 失败退避: Too many parts 时指数退避, 别立即重试
-- 5. 单线程写入: 同一表别多线程并发写, 会产生大量小 part
--
-- 【服务端配置】对应 config/users.xml
-- 1. max_parts_in_total: 100000 (默认300, 绝对不够)
-- 2. background_pool_size: 16 (默认8, 增加合并线程)
-- 3. min_bytes_for_wide_part: 10M (避免大量 tiny part)

-- ============================================================
-- 6. 性能诊断与监控 SQL
-- ============================================================

-- 6.1 查看各表的 part 数量 (判断是否 Too many parts)
-- SELECT
--     table,
--     count()                               AS parts_count,
--     formatReadableSize(sum(bytes))        AS total_size,
--     sum(rows)                             AS total_rows,
--     min(min_date)                         AS min_date,
--     max(max_date)                         AS max_date
-- FROM system.parts
-- WHERE active
--   AND database = 'cold_chain'
-- GROUP BY table
-- ORDER BY parts_count DESC;

-- 6.2 查看当前正在进行的合并
-- SELECT
--     table,
--     result_part_name,
--     formatReadableSize(total_size_bytes)  AS size,
--     total_parts,
--     elapsed                               AS elapsed_sec
-- FROM system.merges
-- WHERE database = 'cold_chain'
-- ORDER BY table, elapsed DESC;

-- 6.3 查看各分区的 part 分布
-- SELECT
--     partition,
--     count()                               AS parts_count,
--     formatReadableSize(sum(bytes))        AS size,
--     sum(rows)                             AS rows
-- FROM system.parts
-- WHERE active
--   AND database = 'cold_chain'
--   AND table = 'temperature_record'
-- GROUP BY partition
-- ORDER BY partition DESC;

-- 6.4 查看写入失败/成功次数
-- SELECT
--     event_date,
--     sum(ProfileEvents['InsertedRows'])   AS inserted_rows,
--     sum(ProfileEvents['InsertedBytes'])  AS inserted_bytes,
--     sum(ProfileEvents['FailedInsert'])   AS failed_inserts
-- FROM system.query_log
-- WHERE type = 'QueryFinish'
--   AND query_kind = 'Insert'
--   AND databases = ['cold_chain']
-- GROUP BY event_date
-- ORDER BY event_date DESC
-- LIMIT 7;

-- 6.5 手动触发合并 (只在紧急情况下使用)
-- OPTIMIZE TABLE cold_chain.temperature_record FINAL;

-- ============================================================
-- 7. 分布式版本示例 (如果用集群, 建表语句如下)
-- ============================================================
/*
-- 本地表
CREATE TABLE IF NOT EXISTS cold_chain.temperature_record_local ON CLUSTER default_cluster
(
    ... 字段同上 ...
)
ENGINE = ReplicatedMergeTree('/clickhouse/tables/{shard}/cold_chain/temperature_record_local', '{replica}')
... 其余配置同上 ...;

-- 分布式表 (读写入口)
CREATE TABLE IF NOT EXISTS cold_chain.temperature_record ON CLUSTER default_cluster
AS cold_chain.temperature_record_local
ENGINE = Distributed('default_cluster', 'cold_chain', 'temperature_record_local', rand());
*/

-- ============================================================
-- 8. 常用查询示例
-- ============================================================
-- 查询某设备最近1小时数据
-- SELECT * FROM cold_chain.temperature_record
-- WHERE device_id = 'DEV001'
--   AND report_time >= now() - INTERVAL 1 HOUR
-- ORDER BY report_time;

-- 查询某车辆今日最高/最低温度
-- SELECT min_temp, max_temp, avg_temp, high_temp_count, low_temp_count
-- FROM cold_chain.mv_vehicle_temp_daily
-- WHERE vehicle_no = '京A12345'
--   AND day_start = today();

-- 查看数据库中所有表
-- SHOW TABLES FROM cold_chain;
