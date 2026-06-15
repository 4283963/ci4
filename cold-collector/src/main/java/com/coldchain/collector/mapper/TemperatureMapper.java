package com.coldchain.collector.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.coldchain.common.entity.TemperatureRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TemperatureMapper extends BaseMapper<TemperatureRecord> {

    void batchInsert(@Param("list") List<TemperatureRecord> list);
}
