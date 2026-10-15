package com.splitbuddy;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * split-buddy 启动类（AA 记账，简历项目一）。
 *
 * <p>分层结构（计划书第 4 章）：Controller 参数校验 -> Service 事务边界 -> Mapper。
 * 结清算法在 domain/debt 包，与 Spring 解耦成纯函数，方便单测。</p>
 */
@SpringBootApplication
// 扫描所有模块的 MyBatis-Plus Mapper 接口，避免每个接口都挂 @Mapper
@MapperScan("com.splitbuddy.**.mapper")
// W2 的到期提醒定时任务依赖 @Scheduled，先打开开关
@EnableScheduling
public class SplitBuddyApplication {

    public static void main(String[] args) {
        SpringApplication.run(SplitBuddyApplication.class, args);
    }
}
