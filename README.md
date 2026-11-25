# CodexWeb

免 Root 的 Android 单 APK，把一套完整的 AI 编码 Web 工作台塞进手机：**proot + Alpine + Python 标准库后端 + WebView 本地 HTTP/SSE**，直接对接 DeepSeek（或其他 OpenAI 兼容端点）。不需要 Termux、不需要 root、不需要 Gradle。

- 包名：`com.aishell.codexweb`
- 本地服务端口：`8123`
- 鉴权：一次性 token + HttpOnly Cookie + PIN/HMAC + 带凭证的 launch URL
- 会话数据、收藏、设置都存本地，多端通过乐观锁（`base_rev`）同步

---

## 功能

- **多种工作模式**：纯对话（极简直连）、轻量基础工具、全能、手机控制（Shizuku）、custom
- **DeepSeek 原生能力**：深度思考（`thinking` 参数）、智能搜索（`deepseek_web_search` 工具）、流式输出与推理展示
- **会话管理**：全局搜索、置顶分组、自动标题、重命名、批量删除/清空、消息收藏、历史审查
- **消息操作**（DeepSeek/WorkBuddy 风格图标）：
  - AI 回复：复制 / 重新生成 / 分享（调起系统分享）/ 收藏
  - 用户消息：复制 / 修改（内联编辑，从该轮起重算）
- **上下文省 Token**：稳定前缀 + 冻结摘要快照 + 纯追加历史 + 缓存命中统计
- **手机能力编排**：用户自制技能模块 + AI 编排（禁止 AI 直接调用 `am`/`input`/`cxdevice` 裸控机）
- **工具链**：内置 apktool / jadx jar，可勾选安装 Java 等环境；抓包、APK 分析、终端
- **后台保活**：前台服务 + 电池优化引导

---

## 目录结构

```
.
├── AndroidManifest.xml          # 应用声明、权限、组件
├── java/                        # Java 源码
│   ├── com/aishell/codexweb/    # MainActivity / BackendService / RuntimePayload / Rootfs / PhoneControlBridge
│   └── moe, rikka/              # Shizuku AIDL 与 API 存根
├── aidl/                        # Shizuku 服务接口
├── res/                         # 图标与字符串
├── src/                         # 运行时的“单一真源”
│   ├── backend/codexd.py        # Python 标准库 HTTP/SSE 后端
│   ├── frontend/index.html      # WebView UI（单文件）
│   ├── guest/launch.sh          # guest 内启动脚本
│   └── version                  # 运行时版本号
├── tools/                       # 构建脚本（见下）
├── assets/payload/              # 运行时载荷（大二进制不在本仓库，见“构建”）
└── build/                       # 构建产物（.gitignore 排除，含签名密钥，绝不入库）
```

---

## 构建

构建链路无 Gradle，直接用 `aapt → javac → d8 → zipalign → apksigner`。

### 前置条件

- **Android SDK**：platform android-34、build-tools 34.0.0（`aapt`/`aidl`/`zipalign`/`apksigner`/`d8.jar`）
- **JDK 17+**
- **运行时载荷**（二进制，未随仓库提供，需自行生成/放置到 `assets/payload/`）：
  - `rootfs.tar.gz`：Alpine aarch64 minirootfs（含 python3、ca-certificates、codex）≈159 MB
  - `bin/{proot,loader,loader-m32,proot-loader}`：Android arm64 交叉编译的 proot
  - `codexweb/tools/{apktool.jar,jadx.jar}`：可从官方发布下载
  - `codexweb/{codexd.py,launch.sh,ui/index.html}` 和 `VERSION`：由构建脚本从 `src/` 自动同步，无需手动放

> 这些大文件被 `.gitignore` 排除，是因为它们超过/接近 GitHub 100 MB 单文件限制，且可从源码或官方渠道重建。`assets/payload/codexweb/` 下的文本文件和 `VERSION` 仍然在仓库中。

### 生成运行时载荷

在 Linux/Termux 上：

```bash
bash tools/build-proot.sh      # 交叉编译 proot 四个二进制（需 NDK + clang）
bash tools/build-payload.sh    # 下载 Alpine、打 rootfs.tar.gz，生成 stage
bash tools/build-apk.sh        # 从 stage 组装并签名 APK
```

`build-payload.sh` 的产物在 `/tmp/cx-payload/out/stage`，`build-apk.sh` 会把它复制到 `assets/payload/`。

### 在 Windows 上构建

如果你已经把 `assets/payload/` 准备齐全（例如从已有环境拷贝），直接用：

```bash
bash tools/build-apk-windows.sh
```

该脚本会：把 `src/` 同步进 `assets/payload/` → aapt 打包 → aidl → javac → d8 → zipalign → 用 `build/codexweb.jks` 签名 → 输出 `build/dist/CodexWeb.apk`。
SDK/JDK 路径可通过环境变量 `SDK` / `JDK` 覆盖（脚本内有默认值）。

> 签名密钥 `build/codexweb.jks` **不在仓库中**。首次构建脚本会自动生成一个新的自签名证书；如果想和已有版本保持同一签名身份，请自行把 keystore 放到 `build/` 下（密码/别名见脚本变量）。

---

## 安全说明

- API Key 只在本地配置，不回显、不写日志；上下文审查记录会自动屏蔽疑似密钥。
- 载荷 asset 以 STORED 方式打包，保证设备端按字节原样拷贝。
- 本机控制严格走“用户自制模块 + AI 编排”的白名单动作，模型不会获得任意 shell 控机能力。

---

## 许可

见仓库 LICENSE（如未提供，默认仅供个人学习与自用）。
