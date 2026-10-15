# Split Buddy 前端（Vue 3 + Vite + Element Plus + Pinia）

AA 记账前后端分离演示端。技术栈：

| 层 | 选型 |
|---|---|
| 框架 | Vue 3（Composition API，纯 JavaScript，无 TypeScript） |
| 构建 | Vite 6（`@vitejs/plugin-vue`） |
| 组件库 | Element Plus 2.x，暗色主题（`<html class="dark">` + 全量样式引入），并用自家 CSS 变量覆写其令牌 |
| 状态 | Pinia（`auth` / `ledger` / `ui` 三个 store） |
| 路由 | vue-router 4（hash 模式，静态托管免 rewrite 配置） |
| 请求 | axios 统一封装（拆 `{code,msg,data}`、Bearer 注入、401 踢回登录、错误 Toast、API 日志） |
| 图表 | 手绘 SVG 组件（净额发散条形图 / 结清流向图），未引入 echarts |

后端是 `../` 里的 Spring Boot（端口 **8081**），本目录不打包进后端 jar——
前后端分离的物理形态不变：**两份独立产物，靠 HTTP 协作**。
npm 镜像已在 `frontend/.npmrc` 固定为 `https://registry.npmmirror.com`（仅本项目生效）。

## 开发：`npm run dev`（连后端 8081）

```bash
# 终端 1：起后端
cd ..
mvn spring-boot:run          # 或 IDEA 运行 SplitBuddyApplication，监听 :8081

# 终端 2：起前端
cd frontend
npm install                  # 走 .npmrc 的 npmmirror 镜像
npm run dev                  # http://localhost:5173（strictPort）
```

`vite.config.js` 里 `server.proxy` 把 `/api/**` 转发到 `http://localhost:8081`：
浏览器永远只请求**同源**相对路径，开发期零 CORS 配置。
前端代码默认也走同源 `/api`；若后端在别的机器，点顶栏的链接 chip
可把 API 基址改成绝对地址（后端 CORS 已允许所有来源），选择存 localStorage。

预置演示账号：`alice / alice123`（甲 Alice）、`bob / bob123`（乙 Bob）。
未登录时点「一键初始化双人演示」即可完成 注册→登录→建账本→邀请→记两笔。

## 生产：`npm run build` 与部署

```bash
cd frontend
npm run build                # 产物在 dist/（index.html + assets/*.js|css）
```

推荐 Nginx 同域反代（部署后浏览器不做跨域检查，前端代码零改动）：

```nginx
server {
    listen 80;
    # 前端静态产物
    location / {
        root /var/www/split-buddy/dist;   # 即本目录 build 出的 dist/
        try_files $uri $uri/ /index.html; # hash 路由其实不需要，留作兼容
    }
    # 后端 API 同域反代
    location /api/ {
        proxy_pass http://127.0.0.1:8081;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
}
```

要点：
- 前端所有请求都是相对路径 `/api/**`，**没有硬编码域名**，切环境只动 Nginx。
- 生产环境不再需要 vite proxy（那只是 dev server 的开发期能力）；
  后端 `CorsConfig` 的同款"上线收敛"说明依旧成立。
- 登录令牌存 `sessionStorage`（关标签即失效），每个请求由 axios 拦截器
  自动加 `Authorization: Bearer <token>`——无状态认证，不依赖 Cookie。
- 401 统一处理：拦截器清会话、Toast 提示、路由踢回登录页（登录/注册接口
  自身的 401=凭证错误，不触发登出跳转）。

## 目录结构

```
frontend/
├── .npmrc                    # 本项目 npm 镜像（npmmirror）
├── vite.config.js            # 端口 5173/strictPort + dev 期 /api 代理（注释说明生产走 Nginx）
├── index.html                # 入口：<html class="dark"> 暗色主题
├── dist/                     # npm run build 产物（可直接静态托管）
└── src/
    ├── main.js               # 装配 Pinia/Router/Element Plus + 401 会话处理注入
    ├── App.vue               # 外壳：顶栏(品牌/基址 chip/身份徽章)+侧导航+路由区+日志抽屉+Toast
    ├── demo.js               # 一键初始化双人演示（alice/bob 全流程）
    ├── router/index.js       # 路由表 + 会话守卫
    ├── api/http.js           # axios 封装：拆 {code,msg,data}/Bearer/401/日志/Toast
    ├── api/index.js          # Backend 接口清单（与 Controller 一一对应）
    ├── stores/auth.js        # 双身份令牌、userId、sessionStorage 持久化、通讯录
    ├── stores/ledger.js      # 当前账本、净额、明细分页
    ├── stores/ui.js          # Toast、API 日志抽屉、API 基址、busy 锁
    ├── styles/tokens.css     # 设计令牌（#0b1020/emerald→sky/圆角12/tabular-nums）+ EP 变量覆写
    ├── styles/base.css       # 深空玻璃拟态全局样式（自旧版 app.css 迁移）
    ├── views/                # AuthView/OverviewView/ExpenseView/SettlementView/MembersView
    └── components/           # NetBarsChart/FlowGraph(手绘 SVG)、IdentitySwitcher、
                              # ApiLogDrawer、ToastHost、EmptyState、DemoZone
```

## 功能地图（左侧导航）

| 页面 | 对应接口 |
|---|---|
| 概览 | `POST/GET /api/ledgers`（账本卡 + myNet）、`GET /api/ledgers/{id}/balances`（净额发散条形图，SVG rAF 补间） |
| 记账 | `POST /api/ledgers/{id}/expenses`（均摊 EQUAL `share:0` / 按金额 AMOUNT 两种分摊）、`GET .../expenses?page=&size=`（分页明细 + 按 version 删除）、`DELETE /api/expenses/{id}?version=` |
| 结清 | `GET /api/ledgers/{id}/settlement-plan`（SVG 流向图，箭头描线动画）、`POST .../settlements/execute-plan?confirm=true`（执行后净额条动画归零） |
| 成员 | `POST /api/ledgers/{id}/members`（按用户名邀请）+ 成员名单 |
| 演示模式 | 左下/登录页"一键初始化"：注册→登录→建账本→邀请→记两笔；顶栏「切换」在 alice/bob 双身份间轮转，走完 记账→结清→执行 闭环 |

底部 **API 日志抽屉**记录每个请求的方法/路径/状态/耗时/响应摘要（后端没起或代理不通会标红）。

## 已知降级说明

后端未提供 `GET /api/ledgers/{id}/members` 成员列表接口，前端成员名单由
`GET .../balances`（净额行含 userId+nickname）与本浏览器登录过的身份推导，
对演示闭环无影响；若接入第三方成员，建议后端补该接口。

> 注：旧版"无 Node 环境的 file:// 直开降级方案"随原生三件套（index.html +
> assets/app.css + assets/app.js）一并移除——本项目现为标准 Vite 工程，
> 需要 Node ≥ 18 进行开发与构建；交付物为 `dist/` 静态产物。
