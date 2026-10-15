package com.splitbuddy.module.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.splitbuddy.module.audit.entity.AuditLog;

/** 审计日志 Mapper：只有插入需求，读取排障时手工查库（Could 级：不做查询接口） */
public interface AuditLogMapper extends BaseMapper<AuditLog> {
}
