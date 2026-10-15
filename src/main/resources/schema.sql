-- split-buddy 建表脚本（对应计划书第 6 章数据库设计）
-- 幂等设计：全部 IF NOT EXISTS，配合 application.yml 的 sql.init.mode=always 每次启动安全执行。
-- 金额一律 DECIMAL(10,2)，禁止 DOUBLE/float（精度丢失是记账系统的经典事故）。

CREATE TABLE IF NOT EXISTS t_user (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '用户ID',
  username      VARCHAR(64)  NOT NULL COMMENT '登录名',
  password_hash VARCHAR(128) NOT NULL COMMENT 'BCrypt 散列，绝不存明文',
  nickname      VARCHAR(64)  NOT NULL COMMENT '展示名',
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_username (username)
) ENGINE=InnoDB COMMENT='用户表';

CREATE TABLE IF NOT EXISTS t_ledger (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '账本ID',
  name       VARCHAR(64) NOT NULL COMMENT '账本名，如 毕业旅行',
  owner_id   BIGINT NOT NULL COMMENT '创建者',
  currency   VARCHAR(8) NOT NULL DEFAULT 'CNY',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_owner (owner_id)
) ENGINE=InnoDB COMMENT='账本表';

CREATE TABLE IF NOT EXISTS t_ledger_member (
  id        BIGINT PRIMARY KEY AUTO_INCREMENT,
  ledger_id BIGINT NOT NULL,
  user_id   BIGINT NOT NULL,
  joined_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 防重复邀请；也是"成员判定"的索引来源（按 user_id 查我的账本列表）
  UNIQUE KEY uk_ledger_user (ledger_id, user_id),
  KEY idx_user (user_id)
) ENGINE=InnoDB COMMENT='账本成员表';

CREATE TABLE IF NOT EXISTS t_expense (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  ledger_id    BIGINT NOT NULL,
  title        VARCHAR(128) NOT NULL,
  amount       DECIMAL(10,2) NOT NULL COMMENT '支出总额，必须等于各分摊行之和',
  paid_by      BIGINT NOT NULL COMMENT '付款人 user_id',
  expense_date DATE NOT NULL,
  due_date     DATE NULL COMMENT '期望结清日，到期提醒扫描用',
  version      INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号（计划书5.3）',
  deleted      TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除，MyBatis-Plus 自动处理',
  created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_ledger_date (ledger_id, expense_date)
) ENGINE=InnoDB COMMENT='支出表';

CREATE TABLE IF NOT EXISTS t_expense_split (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  expense_id BIGINT NOT NULL,
  user_id    BIGINT NOT NULL,
  share_type VARCHAR(16) NOT NULL COMMENT 'EQUAL均摊/RATIO份额/AMOUNT指定金额',
  amount     DECIMAL(10,2) NOT NULL COMMENT '该人承担金额',
  UNIQUE KEY uk_expense_user (expense_id, user_id),
  KEY idx_expense (expense_id)
) ENGINE=InnoDB COMMENT='支出分摊明细';

CREATE TABLE IF NOT EXISTS t_balance (
  id        BIGINT PRIMARY KEY AUTO_INCREMENT,
  ledger_id BIGINT NOT NULL,
  user_id   BIGINT NOT NULL,
  net       DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '净额：正=应收，负=应付；全账本求和恒为0（对账不变量）',
  UNIQUE KEY uk_ledger_user (ledger_id, user_id)
) ENGINE=InnoDB COMMENT='净额物化表，可全量重算（见计划书10 风险）';

CREATE TABLE IF NOT EXISTS t_settlement (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  ledger_id  BIGINT NOT NULL,
  from_user  BIGINT NOT NULL COMMENT '付款方（原债务人）',
  to_user    BIGINT NOT NULL COMMENT '收款方（原债权人）',
  amount     DECIMAL(10,2) NOT NULL,
  source     VARCHAR(16) NOT NULL DEFAULT 'MANUAL' COMMENT 'EXEC_PLAN算法方案执行/MANUAL手动登记',
  settled_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_ledger (ledger_id)
) ENGINE=InnoDB COMMENT='转账/还钱记录';

CREATE TABLE IF NOT EXISTS t_audit_log (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id     BIGINT NOT NULL,
  action      VARCHAR(64) NOT NULL COMMENT '如 EXPENSE_UPDATE',
  target_type VARCHAR(32) NOT NULL,
  target_id   BIGINT NOT NULL,
  detail      VARCHAR(2048) NULL COMMENT 'JSON 摘要',
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_target (target_type, target_id)
) ENGINE=InnoDB COMMENT='操作审计（W2 接 AOP 写入）';
