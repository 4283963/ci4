-- ============================================================
-- MySQL 初始化脚本 - 冷链监控报警数据库
-- 适用版本: MySQL 5.7+ / 8.0+
-- ============================================================

CREATE DATABASE IF NOT EXISTS cold_chain
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_general_ci;

USE cold_chain;

-- ============================================================
-- 报警记录表
-- ============================================================
DROP TABLE IF EXISTS `alarm_record`;
CREATE TABLE `alarm_record` (
    `id`              BIGINT          NOT NULL                COMMENT '雪花ID主键',
    `alarm_id`        VARCHAR(64)     NOT NULL                COMMENT '报警唯一ID',
    `device_id`       VARCHAR(64)     NOT NULL DEFAULT ''     COMMENT '设备ID',
    `vehicle_no`      VARCHAR(32)     NOT NULL DEFAULT ''     COMMENT '车牌号',
    `alarm_type`      TINYINT         NOT NULL DEFAULT 0      COMMENT '报警类型:1-高温 2-低温',
    `alarm_message`   VARCHAR(512)    NOT NULL DEFAULT ''     COMMENT '报警内容',
    `current_value`   DECIMAL(10,2)   NOT NULL DEFAULT 0      COMMENT '当前温度',
    `threshold`       DECIMAL(10,2)   NOT NULL DEFAULT 0      COMMENT '阈值',
    `longitude`       DECIMAL(10,6)   NOT NULL DEFAULT 0      COMMENT '经度',
    `latitude`        DECIMAL(10,6)   NOT NULL DEFAULT 0      COMMENT '纬度',
    `vehicle_status`  TINYINT         NOT NULL DEFAULT 0      COMMENT '车辆状态:0-未知 1-行驶 2-休息',
    `alarm_level`     TINYINT         NOT NULL DEFAULT 1      COMMENT '报警级别:1-低 2-中 3-高',
    `process_status`  TINYINT         NOT NULL DEFAULT 0      COMMENT '处理状态:0-待处理 1-已处理',
    `alarm_time`      DATETIME        NOT NULL                COMMENT '报警时间',
    `process_time`    DATETIME        NULL                    COMMENT '处理时间',
    `create_time`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_alarm_id` (`alarm_id`),
    KEY `idx_device_id` (`device_id`),
    KEY `idx_vehicle_no` (`vehicle_no`),
    KEY `idx_alarm_time` (`alarm_time`),
    KEY `idx_alarm_type_level` (`alarm_type`, `alarm_level`),
    KEY `idx_process_status` (`process_status`),
    KEY `idx_vehicle_status` (`vehicle_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='冷链报警记录表';

-- ============================================================
-- 设备信息表 (可选, 用于设备管理)
-- ============================================================
DROP TABLE IF EXISTS `device_info`;
CREATE TABLE `device_info` (
    `id`              BIGINT          NOT NULL AUTO_INCREMENT COMMENT '主键',
    `device_id`       VARCHAR(64)     NOT NULL                COMMENT '设备ID',
    `device_name`     VARCHAR(128)    NOT NULL DEFAULT ''     COMMENT '设备名称',
    `vehicle_no`      VARCHAR(32)     NOT NULL DEFAULT ''     COMMENT '绑定车牌号',
    `temp_upper`      DECIMAL(10,2)   NOT NULL DEFAULT 8      COMMENT '温度上限',
    `temp_lower`      DECIMAL(10,2)   NOT NULL DEFAULT -18    COMMENT '温度下限',
    `status`          TINYINT         NOT NULL DEFAULT 1      COMMENT '状态:1-启用 0-停用',
    `manager_phone`   VARCHAR(20)     NOT NULL DEFAULT ''     COMMENT '负责人电话',
    `manager_email`   VARCHAR(128)    NOT NULL DEFAULT ''     COMMENT '负责人邮箱',
    `create_time`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_device_id` (`device_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='冷链设备信息表';

-- 初始化一些测试设备
INSERT INTO `device_info` (`device_id`, `device_name`, `vehicle_no`, `temp_upper`, `temp_lower`, `manager_phone`, `manager_email`) VALUES
('DEV001', '冷藏车01号温控器', '京A88888', 8.00, -18.00, '13800138001', 'driver1@coldchain.com'),
('DEV002', '冷藏车02号温控器', '京B66666', 8.00, -18.00, '13800138002', 'driver2@coldchain.com'),
('DEV003', '冷藏车03号温控器', '沪C99999', 10.00, -20.00, '13800138003', 'driver3@coldchain.com'),
('DEV004', '冷冻车01号温控器', '粤D11111', -10.00, -25.00, '13800138004', 'driver4@coldchain.com');
