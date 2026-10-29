# AI 求职助手

## 项目简介

AI 求职助手是一个在本地运行的求职管理工具，使用 Java 21、Spring Boot、SQLite 和 Next.js 构建。它把岗位搜索、条件筛选、AI 匹配评分、沟通草稿与投递分析集中在网页管理界面，支持 BOSS 直聘、猎聘、前程无忧和智联招聘。

本整理版基于上游 GET JOBS 项目，保留既有业务逻辑，补充环境变量读取、空白数据库初始化和发布前的隐私检查。项目名称、说明和使用文档使用中文；GitHub 仓库地址使用拼音标识 `ai-qiuzhi-zhushou`。

## 界面预览

<p align="center">
  <img src="./docs/images/01-services.png" alt="服务与通知配置界面" width="900">
</p>

<p align="center"><em>服务与通知：配置 AI 服务与企业微信通知</em></p>

<p align="center">
  <img src="./docs/images/02-job-profile.png" alt="岗位画像配置界面" width="900">
</p>

<p align="center"><em>岗位画像：设置求职方向、筛选条件与投递偏好</em></p>

<p align="center">
  <img src="./docs/images/03-boss-auto-apply.png" alt="BOSS 直聘配置界面" width="900">
</p>

<p align="center"><em>BOSS 直聘：配置发送模式与岗位投递意向</em></p>


## 功能说明

| 功能 | 说明 |
| --- | --- |
| 多平台配置 | 在网页配置城市、关键词、薪资、学历和经验等条件，各平台能力有所不同。 |
| AI 岗位匹配 | 按岗位要求、工作职责与求职偏好评分，支持个人画像、岗位意向和匹配阈值。 |
| 沟通草稿 | BOSS 支持生成个性化打招呼语，默认采用逐条确认模式。 |
| 投递队列 | 管理待确认、已发送和结果未知的记录，保留去重、暂停与限速状态。 |
| 筛选与黑名单 | 支持薪资条件、企业黑名单和部分平台的 HR 活跃度筛选。 |
| 数据分析 | 查看平台投递记录、关键词和薪资分布等统计。 |
| 可选通知 | 通过自行配置的企业微信机器人推送运行信息。 |
| 隐私保护 | 密钥使用环境变量或本地 `.env`，数据库、Cookie、浏览器会话与日志不入库。 |

详细的评分权重、平台差异和投递状态说明见 [功能详解](docs/功能详解.md)。

## 安装与使用

### 1. 环境要求

- JDK 21，并将 `JAVA_HOME` 指向该 JDK。
- Node.js 22.14 或以上版本，用于前端构建和 SQLite 初始化脚本。
- pnpm，用于安装前端依赖；仓库已附 `pnpm-lock.yaml`。
- Git；Gradle 使用仓库自带的 Wrapper，无需单独安装。
- Python 3.10 或以上为可选项，仅使用 `run.py` 时需要。
- 本机图形桌面与可用的 Chrome/Chromium；首次安装依赖与浏览器驱动需要网络。

### 2. 获取代码

在仓库网页选择 **Code → HTTPS**，复制克隆地址后执行：

```bash
git clone <仓库的HTTPS地址> ai-qiuzhi-zhushou
cd ai-qiuzhi-zhushou
```

### 3. 创建本地配置

Windows PowerShell：

```powershell
Copy-Item .env.example .env
```

macOS / Linux：

```bash
cp .env.example .env
```

在本地 `.env` 中填写自己的配置，或者使用同名的系统环境变量：

```dotenv
BASE_URL=https://api.openai.com
API_KEY=your-api-key
MODEL=your-model-name
HOOK_URL=
BOT_IS_SEND=false
```

| 变量 | 用途 |
| --- | --- |
| `BASE_URL` | OpenAI 兼容接口的基础地址，请按服务商说明设置。 |
| `API_KEY` | 个人 API 密钥；示例值不可直接调用。 |
| `MODEL` | 当前服务可用的模型名称。 |
| `HOOK_URL` | 可选的企业微信机器人地址，默认留空。 |
| `BOT_IS_SEND` | 是否启用通知，默认 `false`。 |

这五项配置优先读取**系统环境变量**，未定义时读取本地 **`.env`**；所得值为空时，回退到**网页保存的数据库配置**。修改环境变量或 `.env` 后需重启后端；使用外部配置时，同名网页设置会被覆盖。其余业务设置仍由网页管理。`.env` 不会提交到 Git，示例文件只能填写占位符。

### 4. 初始化空白数据库

首次运行，在项目根目录执行：

```bash
node scripts/init-db.mjs
```

脚本创建 `db/getjobs.db`，只导入表结构和城市、行业、学历、薪资等公共选项，不包含简历、个人画像、密钥、Cookie、投递记录或黑名单。若目标文件已存在，脚本会停止，避免覆盖已有数据。升级时直接保留自己的 `db/`。

### 5. 安装与构建前端

```bash
cd front
pnpm install --frozen-lockfile
node node_modules/next/dist/bin/next build
node scripts/copy-dist.mjs
cd ..
```

前端静态页面复制到 `src/main/resources/dist/`。依赖和编译结果由本机生成，不上传到仓库。

### 6. 启动后端

Windows PowerShell：

```powershell
.\gradlew.bat bootRun
```

macOS / Linux：

```bash
chmod +x gradlew
./gradlew bootRun
```

构建任务会准备浏览器驱动。启动后访问 [本地管理页面](http://127.0.0.1:9527)。后端默认仅监听本机地址。也可在完成上述初始化后使用 `python run.py` 启动；Windows 启动器可核对并重启当前项目的旧实例。

前端开发时，可在 `front/` 执行 `pnpm dev`，访问 [前端开发页面](http://127.0.0.1:6866)，并保持后端运行。

### 7. 配置与运行任务

1. 打开所需平台，按网页提示登录自己的招聘账号。
2. 填写关键词、城市、薪资等条件，按需配置个人画像和 AI 匹配。
3. 保存设置，先使用默认的逐条确认模式检查候选岗位与草稿。
4. 仅在理解并符合平台规则时执行投递，在官方页面核对发送结果。
5. `UNKNOWN` 表示结果待核对，不会自动重发。验证码、登录失效或访问受限需要人工处理。

程序每次新建浏览器会话需要重新登录，不应提交或分发任何登录态。AI 功能可能把填写的个人介绍与岗位文本发送至你配置的服务，请自行决定填写内容。已有回归测试主要使用模拟或隔离页面，不能替代真实平台兼容性验证。

## 目录结构说明

```text
ai-qiuzhi-zhushou/
├── README.md                         # 中文项目简介与安装使用说明
├── LICENSE                           # 上游非商业许可证及必要版权声明
├── .env.example                      # 无凭据的环境变量示例
├── .gitignore                        # 敏感文件与运行产物排除规则
├── .gitattributes                    # 文本换行与二进制规则
├── .github/
│   ├── workflows/privacy-check.yml   # 提交及 PR 的敏感信息检查
│   └── PULL_REQUEST_TEMPLATE.md      # 中文贡献检查模板
├── database/
│   ├── schema.sql                    # 空白数据库的表结构和索引
│   └── options.sql                   # 公共筛选字典，不含用户记录
├── scripts/
│   ├── init-db.mjs                   # 初始化空白 SQLite 数据库
│   └── check-sensitive.mjs           # 检查实际 Git 文件或暂存内容
├── docs/
│   ├── 功能详解.md                   # 评分、队列、状态与平台差异
│   ├── 发布说明.md                   # 整理、脱敏和上传步骤
│   ├── 更新日志.md                   # 既有版本变更记录
│   ├── Detail.md                     # 历史功能文档
│   ├── Dsign.md                      # 历史设计文档
│   └── part_job.md                   # 兼职筛选说明
├── src/
│   ├── main/java/com/getjobs/
│   │   ├── GetJobsApplication.java   # Spring Boot 入口
│   │   ├── application/             # 控制器、业务服务、实体和数据库映射
│   │   └── worker/                  # 各平台任务、浏览器与投递状态管理
│   ├── main/resources/              # 应用配置与启动横幅
│   └── test/java/                   # 后端回归测试
├── front/
│   ├── app/                         # 平台配置与统计页面
│   ├── components/                  # 布局、平台与通用组件
│   ├── lib/                         # API、状态、格式化工具
│   ├── scripts/                     # 前端构建与测试脚本
│   ├── package.json                 # 前端依赖与命令
│   └── pnpm-lock.yaml               # 前端依赖锁文件
├── tests/                            # 启动器回归测试
├── gradle/wrapper/                   # Gradle Wrapper 文件
├── build.gradle.kts                  # 后端依赖与构建任务
├── gradle.properties                 # 通用构建参数，无个人代理设置
├── settings.gradle                   # Gradle 项目设置
├── gradlew / gradlew.bat              # Linux/macOS 和 Windows 构建入口
└── run.py                            # 可选的本地启动器
```

运行时生成的 `db/`、`browser-data/`、`chrome-profile/`、`build/`、`target/`、`front/node_modules/`、前端静态输出及日志均不属于上传内容。历史设计文档可能描述旧结构，以本 README 和现有源码为准。

## 验证与再次上传

后端回归测试（Windows；其他系统将入口换为 `./gradlew`）：

```powershell
.\gradlew.bat test -x buildFrontend -x processResources
```

前端无需启动平台账号的状态测试，在 `front/` 执行：

```bash
npm run test:boss-delivery
npm run test:boss-job-intent
npm run test:platform-session
npm run test:api-liveness
npm run test:platform-delivery
```

后续提交前在项目根目录执行：

```bash
git add .
node scripts/check-sensitive.mjs --staged
git diff --cached --stat
git commit -m "更新功能与中文文档"
git push
```

脚本检测常见令牌、私钥、个人路径、邮箱及部分身份号码，并对比本地 `.env` 中的私密值；仅报告文件位置和类型，不显示敏感原文。自动规则不能识别所有形式的个人资料，新截图、附件和自定义配置仍需人工核对。

## 来源与许可

本项目基于 [上游 GET JOBS 项目](https://github.com/loks666/get_jobs) 整理，沿用 [PolyForm Noncommercial 1.0.0](LICENSE)。仅允许许可证范围内的非商业使用，商业授权应向上游权利人咨询。必要的上游版权声明与来源链接予以保留，未将本整理版宣称为完全原创项目。
