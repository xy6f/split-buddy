package com.splitbuddy.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件配置。
 *
 * <p>注意插件顺序有讲究（官方文档明确要求）：分页插件放后面、
 * 乐观锁插件放前面。乐观锁生效方式：实体字段标 @Version，
 * UPDATE 语句自动追加 "WHERE version = ?"，返回影响行数 0 即冲突
 * ——Service 层据此抛 409（计划书 5.3）。</p>
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 乐观锁：t_expense.version
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        // 分页：MySQL 方言，自动改写 LIMIT
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
