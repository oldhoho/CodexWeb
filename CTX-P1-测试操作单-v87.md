# CTX 自动会话管理 · P1 真机测试操作单（v87）

## 先放心：这一版不会改变你的对话行为

P1 是**只观察**阶段。模型照常在回复末尾输出 `⟦CTX⟧` 标记，UI 会自动隐藏；系统侧的三道门只写日志，**不归档、不重锚、不重注入话题卡**。所以你可以放心拿日常会话来测，不用担心测完会话被改坏。

要验证的是三道门在真机上的实际信号质量，为 P2 是否开启"自动归档 + 回到旧话题重注入"提供数据。

---

## 0. 准备

1. **安装包**：`D:\WorkBuddy\CodexWeb\build\dist\CodexWeb-v87-ctx-and-gate.apk`（版本戳 87）。
2. **模型**：用 `deepseek-flash`（探测和阈值都是按 flash  calibrated 的；pro 太贵不必测）。
3. **会话**：全部场景在**同一个会话**里连续做，中途不要新建会话。
4. **不用预热**：已知话题清单只在压缩快照形成后（`covered>0`）才非空，前十几轮是空的——这是已知限制，不影响 AND 门和触发词验证，直接开测即可。
5. **每轮等回复完整结束**再发下一轮，避免日志交错。

---

## 1. 五组场景（按顺序做，每做完一组导出一次诊断包）

### 批次 A｜普通延续 —— 验证"零额外调用"

连续三轮，同一话题追问：

1. `帮我用 Python 写个快速排序`
2. `改成归并排序吧`
3. `再说下时间复杂度`

**期望日志**：每轮一条 `recall trigger MISS ... (skip judge)`；若标记正常输出，还有 `ctx lifecycle OBSERVE continue`。**不应出现** `recall trigger HIT` 或 `recall verdict`。

> 这一组用来证明：普通对话轮次没有花那 ~600 token 的冷调用。

### 批次 B｜真切换 —— 验证 AND 门放行

紧接 A，输入一个完全无关的话题：

- `今天有什么重大新闻`

**期望日志**：`ctx lifecycle OBSERVE boundary=true ... overlap=0.00 ... (no archival in P1)`，动作记为 `would_archive`；**不应**出现 `ctx boundary VETO`。

> 若这一轮没出现任何 `ctx lifecycle` 行，说明 flash 尾部标记没输出——这也是有效样本（统计标记输出率用），照实记录即可，不是 bug。

### 批次 C｜同话题高重叠 —— 验证 AND 门 veto（机会性）

重新起一段：

1. `讲个笑话吧`
2. `来个冷笑话`
3. `再讲一个类似的`

**期望**：如果模型手滑判成新话题，应看到
`ctx boundary VETO sid=... topic=... overlap=0.4x>=0.10 -> treat as continue`。
如果模型正确判成延续、没有 veto 行，也是正常结果——这一组是"抓现行"，不保证一定触发。

### 批次 D｜明确触发词回旧话题 —— 核心样本 ⭐

1. `我们在做一个叫 CodexWeb 的项目，重点是自动管理上下文和省 token`
2. `讲讲三体这本书`（切走）
3. `回到刚才 CodexWeb 的话题`（明确召回）

**期望日志链**（第 3 轮）：
```
recall trigger HIT sid=... turn=... word=回到刚才 -> judge
recall verdict    sid=... turn=... decision=true|false
ctx recall judge  sid=... topic=... (independent call)   ← decision=true 时才有
ctx lifecycle OBSERVE recall=true ... would_recall
```

> 这一组是回测的主样本。第 3 轮你心里清楚"确实回到了旧话题"，标注时可据此确认。

### 批次 E｜无触发词的语义召回 —— 测漏召率

接 D，再切走一次，然后**不用任何触发词**地提起旧话题：

1. `讲讲明朝历史`（切走）
2. `那个排序的事呢`（指批次 A 的快速排序，但没说"回到/之前"）

**期望**：`recall trigger MISS`。这是**漏召样本**——用来回答 P2 前那个关键问题：触发词门控到底漏掉了多少真实回话题轮次。

---

## 2. 导出与交付

**每做完一组就立刻导出一次**，不要攒到最后。诊断包只带 `log_tail` 最后 200 行，攒多了早期日志会被挤掉。

导出方式和你之前一样：从 App 的"使用说明 → 诊断/导出"生成诊断包，用系统分享存成 txt（就是之前 `文本分享_*.txt` 那种格式）。

**发给我这些**：
- 5 个诊断包 txt（标注 A/B/C/D/E）
- 一句话说明：哪一轮是你认定的"真回旧话题"、哪一轮是"切新话题"（诊断包里已带每轮 `user_input`，我会自动对应，但你确认一句更准）

---

## 3. 日志关键字速查

在诊断 JSON 的 `log_tail` 里搜这些：

| 关键字 | 含义 |
|---|---|
| `ctx lifecycle OBSERVE continue` | 延续轮，标记正常输出 |
| `ctx lifecycle OBSERVE boundary=true` | 模型判切话题且 AND 门放行 |
| `ctx boundary VETO` | 模型判切话题但被 Jaccard 门拦下 |
| `recall trigger HIT` / `MISS` | 触发词命中 / 未命中 |
| `recall verdict` | 独立调用判定结果（true/false 都记） |
| `ctx recall judge` | 独立调用已发出（仅 verdict=true 时） |
| `ctx lifecycle OBSERVE recall=true` | 回到旧话题信号 |
| 完全没有 `ctx lifecycle` 行 | flash 尾部标记未输出（已知不稳） |

---

## 4. 你发回来之后我做什么

1. 跑 `build/_recall_backtest.py --bundle <你的诊断包.txt> --out-dir <目录>`：
   - 自动从 `log_tail` 提取 HIT/MISS/verdict 配对；
   - 自动从 `turns[]` 把每轮 `user_input` 填进标注模板，你不用手抄。
2. 你（或我按你的确认）给每个 HIT 轮标 `ground_truth: true/false`。
3. 算 precision / recall / F1。**F1 ≥ 0.80 才让 P2 自动召回上线**；不达标则召回先做"建议"形态。
4. 同时统计：标记输出率、边界 AND 门 veto 次数、触发词命中率（≈省了多少冷调用）。

---

## 5. 已知限制（别误判为 bug）

- **话题清单早期为空**：只有压缩重锚后才生成，短会话里模型看不到旧话题清单，recall 标记会偏弱——这正是要在真机上量化的。
- **flash 尾部不稳**：探测时有约 40% 轮次整行标记不输出。没日志行 ≠ 功能坏了。
- **200 行截断**：所以必须分批导出。
- **阈值 0.10 是保守值**：中文 unigram+bigram 混合分词下，真切换≈0、同话题 0.10–0.29；宁可少 veto 也不误杀真切换。
