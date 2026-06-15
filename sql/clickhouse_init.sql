-- ============================================================
-- ClickHouse 初始化脚本 - 冷链监控数据库
-- 引擎: MergeTree (时序表最佳实践)
-- 适用版本: ClickHouse 22.x / 23.x / 24.x
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
-- 排序键: device_id + report_time (按设备+时间有序存储, 便于点查)
-- 主键: 与排序键一致
-- TTL: 保留365天数据, 到期自动清理
-- ============================================================
CREATE TABLE IF NOT EXISTS cold_chain.temperature_record
(
    `id`              Int64                    COMMENT '雪花ID主键',
    `device_id`       String                   COMMENT '设备ID',
    `vehicle_no`      String    DEFAULT ''     COMMENT '车牌号',
    `temperature`     Decimal32(2)             COMMENT '温度(℃)',
    `humidity`        Decimal32(2) DEFAULT 0   COMMENT '湿度(%)',
    `longitude`       Decimal32(6) DEFAULT 0   COMMENT '经度',
    `latitude`        Decimal32(6) DEFAULT 0   COMMENT '纬度',
    `status`          Int8        DEFAULT 1    COMMENT '状态:1-正常 0-异常',
    `report_time`     DateTime                 COMMENT '设备上报时间',
    `create_time`     DateTime   DEFAULT now() COMMENT '入库时间'
)
ENGINE = MergeTree()
PARTITION BY toYYYYMM(report_time)
PRIMARY KEY (device_id, report_time)
ORDER BY (device_id, report_time, id)
TTL report_time + INTERVAL 365 DAY
SETTINGS
    index_granularity = 8192,
    min_bytes_for_wide_part = '10M',
    min_rows_for_wide_part = 100000,
    storage_policy = 'default';

-- 索引: 按车牌号查询 (二级跳数索引)
ALTER TABLE cold_chain.temperature_record
    ADD INDEX IF NOT EXISTS idx_vehicle_no vehicle_no TYPE set(1000) GRANULARITY 4;

-- 物化视图: 设备温度统计 - 每小时聚合
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

-- 物化视图: 车辆温度统计 - 每日聚合
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
-- 3. 分布式版本示例 (如果用集群, 建表语句如下)
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
-- 4. 常用查询示例
-- ============================================================
-- 查看表结构
-- DESC cold_chain.temperature_record;

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
