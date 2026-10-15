# split-buddy · AA 记账 / 债务结清工具

> Spring Boot 3.3.5 单体 ｜ JDK 17 ｜ Vue 3 + Vite + Element Plus + Pinia 前端（前后端分离）

朋友聚餐、宿舍囤货、旅行垫钱之后，"谁欠谁多少、怎么转最省事"是真实的麻烦。本工具维护成员间的**净额债务图**，一键算出**最少转账次数**的结清方案，支持均摊/份额/按金额三种分摊与到期提醒。

**项目定位**：简历项目一，覆盖 Spring 全家桶核心——声明式事务、乐观锁并发、AOP 审计、JWT 无状态认证、Redis 缓存与降级容错，外加一个债务结清算法点（贪心 + 零和子集 DP）。

## 功能一览

- 注册/登录（Spring Security + JWT），账本成员制权限（非成员 403）
- 记一笔支出：均摊（EQUAL，余数归付款人）/份额（RATIO）/指定金额（AMOUNT，校验合计=总额）
- 支出改/删：`@Version` 乐观锁，并发冲突返回 409 并提示刷新
- 净额表实时查询（全账本 Σ=0 不变量）、月度统计
- **结清方案**：贪心配对 + 零和子集状态压缩 DP（≤20 人），一键执行生成转账记录
- 到期提醒：定时扫描 + Redis setnx 去重（Redis 缺席自动降级，0 ERROR）
- 操作审计：`@Audited` 注解 + AOP 切面落库
- 前端：深色玻璃拟态，净额发散条形图、结清流向图（手绘 SVG），双人演示模式一键初始化

## 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Spring Boot 3.3.5 / Spring Security 6 / MyBatis-Plus 3.5.9 / MySQL 8 / Redis / JJWT |
| 前端 | Vue 3 · Vite · Element Plus · Pinia · vue-router · 手绘 SVG 图表 |
| 测试 | 47 个单测（结清算法 18 例含属性测试 + HTTP 回归） |

## 快速开始

### 后端（8081）

```bash
# 前置：JDK 17 + MySQL 8（先建库 split_buddy，schema.sql 自动建表）
export SPRING_DATASOURCE_PASSWORD=your-mysql-password   # 见 .env.example
mvn -s settings.xml spring-boot:run
```

### 前端（5173）

```bash
cd frontend && npm install && npm run dev
```

打开页面点 **一键初始化演示**：双账号注册 → 建账本 → 记两笔 → 结清方案 → 一键执行 → 净额归零。

### 测试

```bash
mvn -s settings.xml test
```

## 包结构（一个 src，包即分层）

```
src/main/java/com/splitbuddy/
 ├─ common/        Result / BizException / RedisSafeService（降级容错）
 ├─ domain/debt/   结清算法：贪心 + 零和子集 DP（纯 Java 零 Spring，单测在此）
 ├─ module.<业务>/  dto|entity|mapper|service|controller（user/ledger/expense/balance/settlement/audit/reminder）
 ├─ security/      JwtUtil / JwtAuthFilter / CurrentUser / LoginUser
 ├─ config/        SecurityConfig / MybatisPlusConfig（分页 + 乐观锁插件）
 └─ aop/           @Audited 审计切面
```

## 面试问答索引

| 问什么 | 看哪段代码 |
|---|---|
| 声明式事务、事务一致性 | `ExpenseService`（一笔账动 3 张表）、`SettlementService` |
| 乐观锁 vs 悲观锁 | `Expense.version` + `MybatisPlusConfig`（实测 10 并发恰 1 成功 9 个 409） |
| JWT / 过滤器链 / 权限模型 | `security/` 包、`LedgerService.assertMember`（Service 层强制断言） |
| 债务结清算法 | `domain/debt/DebtSolver`（贪心 O(n) + 零和子集 DP） |
| 缓存与降级容错 | `RedisSafeService`、`ReminderScheduler` |
| AOP 审计 | `aop/Audited` + `AuditAspect` |

## 说明与取舍

- 金额一律 `DECIMAL(10,2)`；均摊余数归付款人（规则固定在单测里）
- `settings.xml` 指向本地仓库 `./m2repo`（clone 后首次构建自动从阿里云镜像下载依赖）
- Git Bash 发含中文 JSON 会按 GBK 编码导致服务端 400（真实踩过），演示 payload 保持 ASCII
- 详细设计见 [项目计划书.md](项目计划书.md)
