package com.coldchain.alarm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.coldchain.common.entity.AlarmRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AlarmRecordMapper extends BaseMapper<AlarmRecord> {
}
