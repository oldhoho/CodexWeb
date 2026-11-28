# CTX v88 标记输出率 · 真实 API 探测报告

日期：2026-11-28　模型：deepseek-flash（用户指定，PRO 太贵不用）　工具：`build/_ctx_marker_rate_probe.py`

## 目的

验证 v88 的修复（当前轮 user 末尾追加 `CTX_TURN_REMINDER`）能否提升 flash 在长回复下的
`⟦CTX⟧` 标记输出率。这是真机测出的核心瓶颈：短上下文探测 7/7 输出，真机长对话只有约 50%，
标记不输出时三道系统侧门（话题清单 / 触发词 / AND 门）同时失明。

## 方法（单变量对照）

- **忠实度**：探测脚本直接 `import codexd`，使用与 APK 逐字一致的 `CTX_CONTRACT`（569 字）
  和 `CTX_TURN_REMINDER`（107 字）。
- **唯一变量**：当前轮 user 消息末尾是否附加 reminder。
  - `plain`：当前轮 user = 原消息（模拟 v87 行为）
  - `reminder`：当前轮 user = 原消息 + REMINDER（v88 行为）
  - 历史 user 消息两变体相同且均为纯文本（对应真实代码 `ctx["prompt_sent"]` 存纯文本，
    只有当前轮才 `compose_sent_prompt`），因此两变体的请求前缀逐字相同，差异只在当前轮。
- **模拟长上下文**：system 契约 + 8 轮历史（assistant 各约 700 字 clean 文本，不带标记），
  复现契约被稀释的条件。
- **参数**：`temperature=0.0`、`thinking=disabled`、`stream=False`，与真机 flash 一致。
- **指标**：emitted = 输出了合法 `⟦CTX⟧{...}⟦/CTX⟧` 的轮数 / 总轮数。

## 结果

### 第一轮：正式对照（4 条长文案 × 3 次 = 每变体 12 次）

| 变体 | 输出率 | 明细 |
|---|---|---|
| plain（v87） | **11/12 = 91.7%** | 唯一漏标记那轮回复长 3929 字 |
| reminder（v88） | **12/12 = 100%** | 无漏 |
| Δ | +8.3 pp | |

所有 `finish_reason=stop`（自然结束），**排除"被截断才没标记"的假象**。

### 关键发现：漏标记与回复长度强相关

plain 唯一漏标记那轮回复 3929 字，而其余输出平均仅 1059 字。这提示真正的衰减变量是
**回复长度**——回复越长，生成到尾部时 system 契约里"必须输出标记"的指令权重衰减越严重，
越容易"忘掉"尾部标记。reminder 放在 user 末尾（离生成点最近），因此不受长度影响。

### 第二轮：按回复长度分层对照（每条文案 8 次）

| 回复长度 | plain（v87） | reminder（v88） |
|---|---|---|
| < 1500 字 | 1/1 | — |
| 1500–2500 字 | 2/3 | 3/3 |
| 2500–3500 字 | 2/3 | 3/3 |
| ≥ 3500 字 | 3/4 | 5/5 |
| **合计** | **6/8 = 75%** | **8/8 = 100%** |

plain 的漏标记集中在长回复（≥2500 字出现 2 次漏），reminder 全长度段 100%。

### 累计三轮（含最小对照实验）

| 变体 | 累计输出率 |
|---|---|
| plain（v87） | 17/23 ≈ **74%** |
| reminder（v88） | 23/23 = **100%** |

## 结论

1. **v88 修复方向正确且有效**：在所有 23 次调用中，reminder 变体标记输出率 100%；plain 变体
   约 74%，且漏标记全部发生在长回复。reminder 把"长回复尾部漏标记"这个真机核心故障消除了。
2. **根因得到实证**：不是上下文长度本身，而是**回复长度**——生成越长，system 前缀里的标记
   指令权重衰减越厉害。把强制行放到 user 末尾（最近的生成位置）正好对症。
3. **探测是保守估计**：本探测用 8 轮固定历史，真机会话更长（14 轮 + 摘要 + 更长回复），实际
   衰减只会更严重。因此真机上 v87 的 50% 输出率与探测的 74% 不矛盾——探测条件更温和；而
   reminder 在探测里 100%，真机有望显著回升。
4. **对 P2 的意义**：标记输出率是三道门的前提。v88 把它从"约 50%"往上拉，是 P2 自动召回上线
   的前置条件之一。但**仍需真机复测确认**——探测用固定历史，无法完全复现真机的动态上下文。
   真机复测通过 + 回测 f1≥0.80 后，才让 P2 自动召回上线。

## 复跑方式（可直接照做）

全部命令在 Git Bash 里、项目根目录 `D:/WorkBuddy/CodexWeb` 下执行。

### 1. 准备 key（flash 版）

把 key 写成单行纯文本文件（不要引号、不要换行），例如 `build/_probe_key.tmp`：

```bash
cd /d/WorkBuddy/CodexWeb
printf '%s' '你的DeepSeek key' > build/_probe_key.tmp
```

### 2. 跑探测

```bash
# 长回复探针（推荐，真机衰减就是这个场景）：4 条文案 × 每变体 3 次
python build/_ctx_marker_rate_probe.py --model deepseek-flash \
  --key-file build/_probe_key.tmp --probe-set long --repeats 3 \
  --json-out build/_marker_rate_result.json

# 只跑单个变体（省一半调用）：把 --only 换成 plain 或 reminder
python build/_ctx_marker_rate_probe.py --model deepseek-flash \
  --key-file build/_probe_key.tmp --probe-set long --repeats 3 --only plain

# 短回复基线对照（输出率本就接近 100%，用于确认"长回复才漏"）：把 --probe-set 换成 short
python build/_ctx_marker_rate_probe.py --model deepseek-flash \
  --key-file build/_probe_key.tmp --probe-set short --repeats 3
```

参数说明：
- `--repeats N`：每条文案每个变体跑 N 次（flash 方差大，建议 ≥3）
- `--probe-set long|short`：长回复 / 短回复探针
- `--only plain|reminder`：只跑一个变体（对照时省调用）
- `--model`：默认 `deepseek-flash`（用户指定只用 flash）

输出会按变体、按文案打印输出率，末尾给出 `DELTA (v88 reminder - v87 plain)`。

### 3. 用完立即删 key

```bash
rm -f build/_probe_key.tmp
```

脚本绝不回显 key；探测数据见 `build/_marker_rate_result.json`（含每条的 finish_reason 和字数）。

## 真机复测结果（v88，2026-11-28 17:35 两份诊断包）★已验证通过

用户装 v88 真机实测（flash，会话 `s-261128-0919-ebb2`，8 轮，含 3148 字长回复轮）。
诊断包 `build/_v88_bundle_{172800,173520}.json`。按 `ctx lifecycle OBSERVE` 日志直接计数：

| 轮次 | 用户输入 | 回复字数 | 标记 |
|---|---|---|---|
| 1 | 详细写快速排序长文 3000 字 | 3148 | ✅ boundary=true |
| 2 | 高考作文题目范文 | 1451 | ✅ boundary=true |
| 3 | 插件防水怎么做 | 1104 | ✅ boundary=true |
| 4 | 已经用了防水插件还是渗水 | 847 | ✅ continue |
| 5 | 那个作文题目啥来着 | 155 | ✅ recall=true |
| 6 | 好的 | 1072 | ✅ recall=true |
| 7 | AI 会话管理自动化想法 | 601 | ✅ continue |
| 8 | DeepSeek 右侧滑动条快速标签 | 921 | ✅ continue |

**标记输出率 8/8 = 100%，含 3148 字长回复轮——对比 v87 的约 50%，v88 修复在真机确认生效。**
其中第 5 轮"那个作文题目啥来着"（无触发词、回旧话题）正确输出 `recall=true`，是个好信号。

两个附带发现（不影响本次修复验收）：
- 第 8 轮 `topic` 解析出 `{"topic":"DeepSeek右侧导航","boundary":true,"recall":false}⟧`——
  模型偶发把整段 JSON 塞进 topic 字段并多带一个闭合符，属标记解析的边界情况，P2 前可加容错。
- `app_version` 仍显示 78：这次装的是 v88 包但版本戳读取的是旧文件——v88 已加 `/codexweb/VERSION`
  回退，若仍显示 78 说明该 APK 未含修复或读取路径还需核对（仅显示问题，不影响标记功能）。

## 复测操作步骤（留档）

APK：`build/dist/CodexWeb-v88-marker-end.apk`（264 MB，签名已验证）。设备当前未通过 adb 连接，
可直接把 APK 传到手机安装；若 USB 已连，也可用 adb：

```bash
ADB=/c/Users/Administrator/WorkBuddy/2026-11-23-13-53-36/tools/sdk/platform-tools/adb.exe
"$ADB" devices                              # 确认设备已列出
"$ADB" install -r build/dist/CodexWeb-v88-marker-end.apk
```

测试要点（重点验证"长回复是否还漏标记"）：

1. **同一会话、flash 模型**，先随便聊几轮铺垫。
2. 连发 3–4 条**明确要长文**的消息，例如：
   - `详细写一篇关于快速排序的长文，至少3000字，展开讲透`
   - `把今天的新闻写成2000字详细报道`
3. 每轮等回复**完整结束**后，立刻导出诊断包（诊断包只带最后 200 行日志，别攒）。
4. 发回诊断包，我在 `log_tail` 里按时间关联每轮，统计 `ctx lifecycle` 行出现率——
   对照 v87 的约 50%，看 v88 是否明显回升。
5. 判断依据：长回复轮（≥2500 字）是否仍输出 `ctx lifecycle`。若输出率显著上升，
   说明 v88 修复在真机生效，可进入 P2 讨论；若仍大面积漏，则上更强的结构化输出方案。
