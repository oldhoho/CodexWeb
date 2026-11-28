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
- **自动会话管理（CTX，P1 观察中）**：模型在回复末尾输出 `⟦CTX⟧{topic,boundary,recall}` 标记（UI 自动隐藏），后端解析落盘但不改历史；冻结前缀内的「已知历史话题清单」帮模型判断"回到旧话题"。独立 recall 调用改为**仅在召回触发词命中时**触发（省约 95% 冷调用）。详见下方「自动会话管理」章节。
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

## 自动会话管理（CTX）

目标：一个窗口长期对话，自动管理上下文，避免话题爆炸和无关旧话题常驻。

**CTX 协议**：模型每轮在回复末尾输出 `⟦CTX⟧{"topic":..,"boundary":..,"recall":..}⟦/CTX⟧`，UI 自动剥离。三态：`boundary=true` 新话题 / `recall=true` 回到旧话题 / 都 false 为延续。

**当前阶段 P1（只观察，不改历史）**：标记经 `ctx_parse_marker` 宽松解析、畸形降级为保守 false，落盘到轮次 `ctx_meta`；`apply_topic_lifecycle` 只记日志（`would_archive` / `would_recall`），不归档、不重锚。

**省 Token 设计（v86，方案①+②）**：
- **① 冻结话题清单**：`known_topics_block()` 只从已进入压缩快照（`turns[:covered]`）的历史 topic 生成清单，随重锚点才变 → 两次重锚之间字节完全一致，缓存命中不受影响；清单随契约进缓存前缀，让模型能可靠标记"回到旧话题"。
- **② 触发词门控**：独立 recall 冷调用（约 600 未缓存 token）原本每轮延续都发，现改为**只在 `recall_trigger_match()` 命中明确召回词**（"回到刚才/之前说的/还是那个/back to that…"）时才发，普通延续零额外调用。命中/未命中/判定结果都记日志，日志经 `scrub()` 脱敏。开关 `RECALL_JUDGE_FALLBACK`（默认 True）。

**③ boundary AND 门（v87，社区 hermes-agent 思路的 stdlib 降级）**：flash 探测同时显示 `boundary=true` 也会手滑（实际还在同话题却报新话题）。模型标记不再单独触发归档——系统侧用**字符级 Jaccard 词法重叠**（中文 unigram+bigram、英文整词，零依赖、零 LLM 成本）交叉验证：本轮与上一轮输入重叠 ≥ `TOPIC_GUARD_MIN_OVERLAP_JACCARD`（0.10，实测真切换≈0、同话题 0.10–0.29，偏保守取 0.10 避免误杀真切换）时 veto 掉 boundary，视为延续。`apply_topic_lifecycle` 产出 `boundary_effective` / `boundary_veto` / `overlap`，P1 只记录，P2 直接读 `boundary_effective` 决定是否归档（单一 funnel，不动调用点）。阈值标定来自实测：社区英文词级参考 0.2–0.3，中文短句标尺不同已下调。

**④ 标记输出率修复（v88）**：真机发现上述三道门都挂在"模型输出了 ⟦CTX⟧ 标记"这个前提上，而 flash 在长对话里标记输出率仅约 50%（短上下文探测 7/7，长对话尾部衰减）——标记不输出时三道门**同时失明**。根因是"必须输出"的契约在 system 前缀开头，被记忆/摘要/全部历史稀释。对策：`compose_sent_prompt()` 在【当前轮 user 消息末尾】追加一条极简强制行 `CTX_TURN_REMINDER`（离生成点最近、权重最高）；关键是不改 `ctx["prompt_sent"]`，历史 replay 字节不变、缓存前缀不 churn。

**真机结论（v87，9 份诊断包）**：P2 自动召回暂不上线。回测 f1=1.0 但仅 1 个标注样本；4 个无触发词回旧话题轮只有 1 轮判对且输出标记、3 轮整行不输出。话题清单始终未验证（14 轮累计约 15000 字 < 24000 压缩阈值，`covered=0`）。**核心瓶颈是标记输出率，不是缺清单**——v88 即针对此修复。分析详见 `CTX-P1-真机测试分析-v87.md`。

**v88 修复的真实 API 探测（flash）**：单变量对照（当前轮 user 末尾是否加 reminder），忠实导入 APK 同款契约。结果：reminder 变体标记输出率 **23/23 = 100%**，plain（v87 行为）约 **74%**，且 plain 的漏标记全部集中在长回复（≥2500 字）——**根因实证为"回复越长、system 前缀里的标记指令权重衰减越严重"**，reminder 放到离生成点最近的 user 末尾正好对症。`finish_reason=stop` 排除截断假象。探测条件（8 轮固定历史）比真机温和，属保守估计。详见 `CTX-v88-标记输出率探测.md`。

**v88 真机复测通过**：装 v88 真机 8 轮（含 3148 字长回复）`ctx lifecycle` 标记 **8/8 = 100%**（对比 v87 约 50%），核心瓶颈确认修复。第 5 轮无触发词回旧话题也正确判 `recall=true`。详见 `CTX-v88-标记输出率探测.md` 真机复测章节。

**v89（当前版本，P2 仍全缓）**：两端修复"流式输出自动滚动无法打断"——CodexWeb 前端 `src/frontend/index.html` 加 `stickToBottom` 粘性跟随（贴底才滚、发送/切会话强制贴底、滚轮/拖动打断）；Lumetro AI 窗 `AiChatOverlay.kt` 在 `followStreamScroll` 异步回调二次校验 `followIntent` 并在拖动时中止平滑动画。另加标记解析容错（单闭合符 `⟧` 误用、未闭合截断仍正确提取 topic 且边界/召回保守为 false）。方案与验证清单见 `流式输出自动滚动修复方案.md`。**P2 归档/重锚/召回继续只观察**，待 recall 样本 f1≥0.80 再放行。

**P2 上线前的离线回测门槛**（用户要求，必须用真机日志验证）：

```bash
# 1) 直接读 App 导出的诊断包（推荐）：log_tail + turns[].user_input 自动填标注模板
python build/_recall_backtest.py --bundle <诊断分享.txt> --out-dir build/backtest

#    （也可读裸日志）python build/_recall_backtest.py --log <codexweb.log> --out-dir build/backtest

# 2) 人工在 build/backtest/annotations.json 给每个 HIT 轮次标 ground_truth (true/false)

# 3) 计算 precision/recall/f1，默认门槛 f1 >= 0.80
python build/_recall_backtest.py --bundle <诊断分享.txt> --annotations build/backtest/annotations.json

# 自测（合成日志，零依赖）
python build/_recall_backtest.py --self-test
```

- **达标（f1 ≥ 0.80）**：P2 按①+②组合上线自动召回（回到旧话题 → 重注入 1~2 张归档话题卡）。
- **不达标**：P2 的召回先以"建议而非自动执行"形态存在（提示用户"这轮像是在回到旧话题 X，是否恢复？"），等 in-body 标记信号质量改善后再升级为自动。

标记解析回归：`python build/_topic_marker_test.py`（含方案①清单字节稳定性、②触发词门控、③ AND 门、④ user 末尾 reminder 不破坏缓存前缀、[17] v89 单闭合符/截断/畸形 JSON 容错测试）。

---

## 安全说明

- API Key 只在本地配置，不回显、不写日志；上下文审查记录会自动屏蔽疑似密钥。
- 载荷 asset 以 STORED 方式打包，保证设备端按字节原样拷贝。
- 本机控制严格走“用户自制模块 + AI 编排”的白名单动作，模型不会获得任意 shell 控机能力。

---

## 许可

见仓库 LICENSE（如未提供，默认仅供个人学习与自用）。
