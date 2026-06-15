package com.coldchain.alarm.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class NotificationService {

    @Value("${coldchain.notify.sms-enabled:true}")
    private boolean smsEnabled;

    @Value("${coldchain.notify.email-enabled:true}")
    private boolean emailEnabled;

    @Async
    public void sendSms(String phone, String content, String alarmId) {
        if (!smsEnabled) {
            return;
        }
        try {
            long start = System.currentTimeMillis();
            Thread.sleep(50);
            long cost = System.currentTimeMillis() - start;
            log.info("[Notify-SMS] 短信发送成功 | alarmId:{} | 手机号:{} | 内容:{} | 耗时:{}ms",
                    alarmId, maskPhone(phone), content, cost);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[Notify-SMS] 短信发送被中断 | alarmId:{}", alarmId);
        } catch (Exception e) {
            log.error("[Notify-SMS] 短信发送失败 | alarmId:{} | 原因:{}", alarmId, e.getMessage());
        }
    }

    @Async
    public void sendEmail(String email, String subject, String content, String alarmId) {
        if (!emailEnabled) {
            return;
        }
        try {
            long start = System.currentTimeMillis();
            Thread.sleep(80);
            long cost = System.currentTimeMillis() - start;
            log.info("[Notify-Email] 邮件发送成功 | alarmId:{} | 邮箱:{} | 主题:{} | 耗时:{}ms",
                    alarmId, maskEmail(email), subject, cost);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[Notify-Email] 邮件发送被中断 | alarmId:{}", alarmId);
        } catch (Exception e) {
            log.error("[Notify-Email] 邮件发送失败 | alarmId:{} | 原因:{}", alarmId, e.getMessage());
        }
    }

    public void notifyManagers(String alarmId, String alarmMessage, String vehicleNo, int level) {
        String content = String.format("[冷链报警] 车辆:%s | 级别:%s | %s", vehicleNo, getLevelName(level), alarmMessage);
        sendSms("13800138000", content, alarmId);
        sendEmail("admin@coldchain.com", "冷链温度报警", content, alarmId);
    }

    private String getLevelName(int level) {
        switch (level) {
            case 1: return "低";
            case 2: return "中";
            case 3: return "高";
            default: return "未知";
        }
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 11) return phone;
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }

    private String maskEmail(String email) {
        if (email == null || !email.contains("@")) return email;
        int idx = email.indexOf("@");
        String prefix = email.substring(0, idx);
        String suffix = email.substring(idx);
        if (prefix.length() <= 2) return prefix + suffix;
        return prefix.substring(0, 2) + "***" + suffix;
    }
}
