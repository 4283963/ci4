# 车载冷链监控微服务平台 - 使用说明

## 一、整体架构

```
        ┌──────────────────────────────────────────────────────────┐
        │                      车载终端设备                          │
        │               (温度/湿度/GPS传感器上报)                     │
        └──────────────────────┬───────────────────────────────────┘
                               │ HTTPS/HTTP POST (JSON)
                               ▼
        ┌──────────────────────────────────────────────────────────┐
        │                cold-gateway (8080)                        │
        │         Spring Cloud Gateway + Nacos 注册发现              │
        │   /api/collect/** → cold-collector                        │
        │   /api/alarm/**   → cold-alarm                            │
        └──────┬───────────────────────────────┬───────────────────┘
               │                               │
               ▼                               ▼
   ┌──────────────────────────┐     ┌──────────────────────────┐
   │   cold-collector (8081)  │     │    cold-alarm (8082)      │
   │                          │     │                          │
   │ ① 接收温度上报 REST API   │     │ ⑤ 接收报警 HTTP POST      │
   │ ② 写入 Kafka 队列         │────▶│ ⑥ 去重 + 持久化(MySQL)    │
   │ ③ Kafka消费→批量写CK      │     │ ⑦ 异步通知(短信/邮件)      │
   │ ④ 温度超标→Feign调用报警  │     │                          │
   └──────────────┬───────────┘     └──────────────────────────┘
                  │
                  ▼
   ┌──────────────────────────────────────────────────────────────┐
   │                    Kafka (8个分区, snappy压缩)                 │
   │                Topic: cold_temperature_data                    │
   └──────────────────────┬───────────────────────────────────────┘
                          │ 批量消费
                          ▼
   ┌──────────────────────────────────────────────────────────────┐
   │              ClickHouse (MergeTree 时序表)                     │
   │  Table: temperature_record (按月分区, 按设备+时间排序)          │
   │  MV:    设备小时聚合 / 车辆日聚合                                │
   └──────────────────────────────────────────────────────────────┘
```

## 二、端口清单

| 服务 | 端口 | 说明 |
|------|------|------|
| Nacos | 8848 | 注册中心 + 配置中心 |
| cold-gateway | 8080 | API 网关入口 |
| cold-collector | 8081 | 数据采集服务 (直接访问) |
| cold-alarm | 8082 | 报警服务 (直接访问) |
| MySQL | 3306 | 报警记录持久化 |
| Kafka | 19092(外部)/9092(内部) | 消息队列 |
| Zookeeper | 2181 | Kafka 协调 |
| ClickHouse | 8123(HTTP)/9000(TCP) | 时序数据库 |

## 三、启动方式

### 方式一: Docker Compose 一键启动（推荐）
```bash
# 1. 先编译所有模块
mvn clean install -DskipTests

# 2. 启动所有服务
docker-compose up -d

# 3. 查看启动状态
docker-compose ps

# 4. 查看日志
docker-compose logs -f cold-collector
docker-compose logs -f cold-alarm
```

### 方式二: 本地开发模式
```bash
# 1. 先启动基础设施 (MySQL + Kafka + ClickHouse + Nacos)
docker-compose up -d mysql kafka clickhouse nacos zookeeper

# 2. 等待 Nacos 就绪后, 本地启动三个 Spring Boot 应用
# cold-gateway → 8080
# cold-collector → 8081
# cold-alarm → 8082
```

## 四、API 使用

### 1. 单条温度数据上报

```bash
curl -X POST http://localhost:8080/api/collect/collect/temperature \
  -H "Content-Type: application/json" \
  -d '{
    "deviceId": "DEV001",
    "vehicleNo": "京A88888",
    "temperature": 5.6,
    "humidity": 65.5,
    "longitude": 116.407526,
    "latitude": 39.90403,
    "status": 1,
    "reportTime": "2025-06-15T10:30:00"
  }'
```

### 2. 批量温度数据上报（高并发场景推荐）

```bash
curl -X POST http://localhost:8080/api/collect/collect/temperature/batch \
  -H "Content-Type: application/json" \
  -d '[
    {"deviceId":"DEV001","vehicleNo":"京A88888","temperature":6.2,"humidity":60,"longitude":116.4,"latitude":39.9,"status":1},
    {"deviceId":"DEV001","vehicleNo":"京A88888","temperature":12.5,"humidity":62,"longitude":116.41,"latitude":39.91,"status":1},
    {"deviceId":"DEV002","vehicleNo":"京B66666","temperature":-25.0,"humidity":55,"longitude":116.3,"latitude":39.8,"status":1}
  ]'
```

> **说明**: 第二条数据温度 12.5℃ 超过上限 8℃，会自动触发高温报警；第三条 -25℃ 低于 -18℃ 会触发低温报警。

### 3. 查看报警统计

```bash
curl http://localhost:8080/api/alarm/alarm/stats
```

### 4. ClickHouse 查询温度数据

```bash
clickhouse-client --host 127.0.0.1 --port 9000 --database cold_chain

-- 查询某设备最近数据
SELECT device_id, vehicle_no, temperature, report_time
FROM temperature_record
WHERE device_id = 'DEV001'
ORDER BY report_time DESC
LIMIT 20;

-- 查看每小时聚合
SELECT * FROM mv_device_temp_hourly
WHERE device_id = 'DEV001'
ORDER BY hour_start DESC
LIMIT 24;

-- 统计数据量
SELECT count() AS total, toDate(report_time) AS dt
FROM temperature_record
GROUP BY dt ORDER BY dt DESC;
```

### 5. MySQL 查询报警记录

```sql
USE cold_chain;

SELECT alarm_id, device_id, vehicle_no, alarm_type, alarm_level,
       current_value, threshold, alarm_message, alarm_time
FROM alarm_record
ORDER BY alarm_time DESC
LIMIT 20;
```

## 五、高并发设计要点

| 优化点 | 实现方式 |
|--------|----------|
| Kafka 生产者 | 批量发送(16KB) + snappy 压缩 + linger.ms=5 平滑抖动 |
| Kafka 消费者 | 8并发分区消费 + 手动ACK + 每批500条 |
| ClickHouse 写入 | 内存队列缓冲 + 2000条或5秒批量刷写 |
| 报警检测 | 异步线程池 + Feign 熔断降级 + 3次重试 |
| 网关 | CORS全局配置 + 请求日志 + Nacos服务发现 |
| 时序表 | MergeTree + 按月分区 + 设备+时间排序 + 365天TTL |

## 六、温度报警阈值（可在代码中调整）

- **高温阈值**: 8℃（高于即报警）
- **低温阈值**: -18℃（低于即报警）
- **报警级别**: 温差<2℃ 低级; 2~5℃ 中级; ≥5℃ 高级
- 阈值常量定义在 `cold-common/.../constant/AlarmConstants.java`
