# 工作流状态机：为什么在「自由」之后，要退回「写死」？—— LangGraph 到底解决了什么

> **学习札记 · ToyAgent Step16**
> 配套代码：[`WorkflowAgent.java`](./WorkflowAgent.java)
> 阅读前提：看过 Step03 ReAct（模型自己决定下一步）、Step12 多智能体（写死的线性流水线）。**这篇要回答的是这两者之间那个「中间地带」**。

---

## 缘起

一路看下来是这样的：

```
Step03 ReAct      → 模型自己决定走哪步（自由）
Step12 多智能体    → 程序员写死一条线（死板）
Step16 工作流      → ？？？
```

Step16 打开一看，我第一反应是**倒退**：

```java
// WorkflowAgent.java:52-62
runNode("classify", state);                       // 固定入口
String next = String.valueOf(state.getOrDefault("next", "faq"));
runNode(next, state);                             // 按分类走分支
runNode("polish", state);                         // 固定汇合点
```

**这不就是 `if/else` 吗？ReAct 都让模型自由决策了，为什么这里反而把路由写死在代码里？**

带着这个问题查了一圈 LangGraph 的资料，我才明白：

> **这不是倒退，是把「控制流」从模型手里，第一次明确地拿回到代码手里。**
> **这个取舍有名字，有数据，也有一堆人踩过的坑。**

这篇分三部分：**先看清这份代码（一~四），再看真实的 LangGraph 长什么样（五~七），最后是参考来源与附录。**

---

## 一、三件套：State / Node / Edge

整份代码 155 行，骨架就是三个概念。

### ① State —— 在节点间流转的共享状态

```java
// WorkflowAgent.java:46-49
Map<String, Object> state = new LinkedHashMap<>();
state.put("input", input);          // 原始输入
state.put("answer", "");            // 最终答案
state.put("observation", "");       // 工具观察结果
```

**一个 `Map`，就是全部状态。** 所有节点都从它读、往它写。

### ② Node —— 一段能力，读 State、写 State

四个节点（`WorkflowAgent.java:72-135`），每个就是一个 `case`：

| 节点 | 干什么 | 读 State | 写 State |
|---|---|---|---|
| `classify` | 意图分类，决定下一步走哪 | `input` | `next` |
| `tool` | 算数 / 查天气 | `input` | `observation` |
| `faq` | 直接问答 | `input` | `answer` |
| `polish` | 统一润色，产出终稿 | `observation` / `answer` | `answer` |

**关键：节点只管「做事」，不管「下一步去哪」。** 路由是边的事，不是节点的事。

### ③ Edge —— 决定下一步

```java
// WorkflowAgent.java:54-59  条件边
String next = String.valueOf(state.getOrDefault("next", "faq"));
lastTrace.add(Map.of("type", "edge", "label", "条件边",
        "detail", "classify --" + next + "--> " + (next.equals("tool") ? "tool 节点" : "faq 节点")));

runNode(next, state);      // 走过去
```

**边就是那个 `if`。** 但它被**显式地写成了一个「边」**，而不是散落在节点内部。

### 整张图长这样

```text
START
  │
  ▼
┌──────────┐
│ classify │    ← 模型在这里做判断：输出 {"next": "tool|faq"}
└──────────┘
  │
  ├── next = tool ──▶ ┌──────┐ ──┐
  │                   │ tool │   │
  │                   └──────┘   │
  │                              ├──▶ ┌────────┐ ──▶ END
  └── next = faq  ──▶ ┌─────┐ ──┘    │ polish │
                      │ faq │        └────────┘
                      └─────┘
```

**两条分支在 `polish` 汇合** —— 这是「图」相对「线」的第一个好处：**分支和汇合**。

---

## 二、跑一遍：state 是怎么一步步变的

### 先立一个比喻：一次工作流 = 看一次病

这篇组件多，先用**看病**把角色对上号：

| 代码里 | 现实里 |
|---|---|
| `state` | **病历本** —— 从进门跟到出门，谁都能往上写 |
| `classify` 节点 | **分诊台护士** —— 不治病，只判断「你该去哪个科」 |
| `tool` / `faq` 节点 | **检验科 / 全科医生** —— 真正干活的 |
| `polish` 节点 | **药房** —— 不管你看的哪个科，最后都来这 |
| **条件边** `next` | 护士在病历本上写的**「内科」** —— 决定你下一步去哪 |
| **固定边** | 「**看完病必须去药房**」—— 谁都躲不掉 |
| START / END | 进医院 / 出医院 |

**三个组件一句话对上**：节点 = 岗位，边 = 交接动作，state = 那本跟着你跑的病历本。

> 为什么强调「**一本**病历本」？因为你从内科转到药房，**本子是跟着你走的** —— 药房打开就知道内科写了啥。要是各科室各拿一本，流程当场断掉。这就是前面说的「state 必须是一份、共享」。

### 走一遍

概念说完了，跑一次就全实了。输入：**`"北京天气怎么样"`**

### ① 建 state（`WorkflowAgent.java:46-49`）

```java
state = { input: "北京天气怎么样", answer: "", observation: "" }
```

### ② `runNode("classify", state)`（`WorkflowAgent.java:52`）

进 `runNode`，`switch` 落到 `case "classify"`：

```java
model.chat([system("[节点:classify] 你是意图分类器，只输出 JSON：{\"next\": \"faq|tool\", ...}"),
            user("北京天气怎么样")])
// 模型返回（假设）：{"next": "tool", "reason": "涉及天气"}

String next = Json.str(parsed, "next");     // → "tool"
state.put("next", "tool");                  // 写进 state
```

```java
state = { input: "北京天气怎么样", answer: "", observation: "", next: "tool" }
```

### ③ 读路由（`WorkflowAgent.java:55`）

```java
String next = String.valueOf(state.getOrDefault("next", "faq"));   // → "tool"
```

**就是伸手到 state 上读 `next`。** 取不到就吃默认值 `"faq"`。

### ④ `runNode("tool", state)`（`WorkflowAgent.java:61`）

落 `case "tool"`。正则匹配 `"北京天气怎么样"` → **不匹配**，走 `else` 分支（`WorkflowAgent.java:103-107`）：

```java
input.contains("上海") / "杭州" / "成都"   → 全 false
city = "北京"
observation = "「" + "北京" + "」" + MockWeather.query("北京")
            = "「北京」晴，26℃"
state.put("observation", "「北京」晴，26℃");
```

```java
state = { input: "北京天气怎么样", answer: "", observation: "「北京」晴，26℃", next: "tool" }
```

### ⑤ `runNode("polish", state)`（`WorkflowAgent.java:62`）

落 `case "polish"`：

```java
obs  = "「北京」晴，26℃"
base = obs.isEmpty() ? state.get("answer") : "根据查询结果：" + obs
     = "根据查询结果：「北京」晴，26℃"

answer = model.chat([system("[节点:polish] 你是润色节点。...前序产出: " + base),
                     user("北京天气怎么样")])
// 模型返回（假设）："北京今天晴，气温 26℃。"
state.put("answer", "北京今天晴，气温 26℃。");
```

```java
state = { input: "北京天气怎么样",
          answer: "北京今天晴，气温 26℃。",
          observation: "「北京」晴，26℃",
          next: "tool" }
```

### ⑥ 返回（`WorkflowAgent.java:68`）

```java
return String.valueOf(state.get("answer"));   // → "北京今天晴，气温 26℃。"
```

**换成 `"你好"`，只有两处不同**：`classify` 返回 `next = "faq"`；第 ④ 步走 `case "faq"`，写的是 `answer` 而不是 `observation`。

**分岔在 `WorkflowAgent.java:55`，合流在 `WorkflowAgent.java:62`。**

---

### 顺带理清关于 `state` 的两个误会

#### 误会一：`next` 这个 key 是从哪来的？

初始只 `put` 了三栏（`input` / `answer` / `observation`），**`next` 是 `classify` 跑的时候新加的**（`WorkflowAgent.java:86`）：

```java
state.put("next", next == null ? "faq" : next);   // ← 这里才第一次出现 "next"
```

**`Map` 的 key 是开着的** —— 谁想加谁 `put`，不用提前声明。那三行初始 `put` 只是「顺手预置几个常用 key」，不是"声明字段"。

**LangGraph 里不会这样**：状态是**类型化 schema**，所有字段必须一次性声明清楚，写错字段名当场报错。**这就是前面「裸 Map vs 类型化 State」那条对比的具体长相。**

#### 误会二：`state` 记不记「执行到哪儿了」？

**不记。** 「走到哪」是 `chat()` 那几行的**书写顺序**在管，不是纸上的内容：

| | 归谁管 |
|---|---|
| 「走 **哪条**」（**决策**） | **`state`** —— 可以放纸上（`next`） |
| 「走到 **哪**了」（**进度**） | **运行时** —— 代码顺序 / LangGraph 的 checkpointer |
| | **~~`state`~~** ← 都不是它 |

> **`state` 是随身行李，装的是路上要用的业务数据；「走到哪了」是司机（运行时）记的，不塞进行李箱。**

---

## 三、核心问题：到底谁在控制「下一步」？

这是全文最重要的一张坐标。

| | **Step03 ReAct** | **Step12 多智能体** | **Step16 工作流** |
|---|---|---|---|
| 谁决定下一步 | **模型**（每轮输出 action） | **程序员**（写死的线） | **程序员定图 + 模型定节点内** |
| 控制流在哪 | 模型脑子里（看不见） | 代码里（一条直线） | 代码里（一张**图**） |
| 灵活 | ✅✅ | ❌ | ✅ |
| 可控 | ❌ | ✅✅ | ✅✅ |
| 能调试吗 | 难（下次可能不一样） | 易 | 易 |
| 能画出来吗 | 不能 | 能（就是那几个角色） | **能（就是这张图）** |

Anthropic 在 [Building Effective Agents](https://www.anthropic.com/engineering/building-effective-agents) 里把这件事说得很清楚 —— **Workflow 的定义就是这一句**：

> **Workflows are systems where LLMs and tools are orchestrated through predefined code paths.**
> （工作流 = LLM 和工具被编排在**预先定义的代码路径**上）

注意这句话的两半：

- **路径（走哪条边）** → **代码定**
- **路径内部（这段做什么、说什么）** → **模型定**

而 ReAct 是「**连走哪条都由模型定**」。

> **Step16 的全部意义，就是把「走哪条」从模型手里拿回来，交给代码 —— 同时保留「这段说什么」给模型。**

**再精确一点**：模型并不是完全不碰路由 —— `classify` 节点里**就是模型在决定 `next`**。但它：

1. **只被问一次**（ReAct 是每步都回头再问）
2. **只能在代码给的选项里选**（`"tool"` 或 `"faq"`，别的值走 `default` 分支）
3. **答完之后，走不走由代码执行**（`WorkflowAgent.java:61`）

**代码画好框，模型在框里填内容。** 这就是「代码定路径、模型定内容」的准确含义。

---

## 四、诚实的部分：这份教学代码简化了什么

先用自己这张表把话说死，免得后面越看越飘：

| | 这份代码 | 真实 LangGraph |
|---|---|---|
| 图怎么定义 | `switch` 里写死 | `add_node` / `add_edge` 声明式构建 |
| 状态是什么 | 裸 `Map<String,Object>` | 类型化 schema（TypedDict / Pydantic） |
| 状态怎么合并 | **直接覆盖** | **Reducer** 决定合并语义 |
| 分支 | `if` | `add_conditional_edges` |
| 持久化 | **无**（内存里一个 `Map`，请求完就没了） | **Checkpointer**（Postgres / SQLite） |
| 人工介入 | 无 | `interrupt()` + `Command(resume=)` |
| 并行 | 无 | super-step 并行 + fan-in |
| 环（循环） | **没有**（只能走一次） | **是核心特征**（重试 / 追问 / 修订） |

**教学代码给你的是「图这个东西长什么样」，不是「生产级的图怎么跑」。**

下面几节，把右边那一列补齐。

---

## 五、LangGraph 的真实设计（调研）

### 5.1 为什么是「图」，不是「链」

早年的做法是**线性链**：提示 → LLM → 响应。问题是真实系统里全是**分支、循环、汇合**，链表达不了，只能拿 `if/while` 硬凑 —— **控制流就藏进了代码角落里，看不见摸不着。**

LangGraph 的第一性选择：**把控制流变成一等公民。**

> **Graph 负责控制结构，State 负责数据流转，Schema 和 Reducer 负责把 State 设计清楚。**

翻译成人话：

| 概念 | 职责 | 一句话 |
|---|---|---|
| **Node（节点）** | 做事 | 「干什么」 |
| **Edge（边）** | 转向 | 「下一步去哪」 |
| **State（状态）** | 数据 | 「带什么走」 |

**三者解耦**，是整件事的地基。你的 `WorkflowAgent` 里，这三样恰好就是 `case` / `next` / `state`。

### 5.2 节点是「纯函数」，只返回增量

这点跟你的代码有个微妙的差别，很值得注意：

```python
# LangGraph：节点拿到只读快照，只返回它关心的那部分
def classify(state: State) -> dict:
    return {"next": "tool"}      # ← 只返回"我要改的字段"，不是整个 state
```

**节点不直接改状态** —— 它返回一个**增量（partial update）**，由框架负责合并。为什么这么设计？

- **可测试**：节点是纯函数 `state -> partial`，没有隐藏全局变量
- **可并行**：多个节点同时写**不同字段**不会冲突
- **可复用**：节点跨工作流搬运，不用改内部逻辑

你的代码里是「节点直接 `state.put(...)`」—— 教学版够用，但一旦要并行、要回溯，直接改动就是麻烦的来源。

### 5.3 Reducer：状态**怎么**合并，是一门单独的学问

这是 LangGraph 里最容易被忽略、又最重要的一块。

**默认行为是「覆盖」（last-write-wins）**：节点返回什么，那个字段就变成什么。

但要**累加**语义怎么办？答案是 `Reducer`：

```python
class State(TypedDict):
    messages: Annotated[list, add_messages]   # ← 追加，不是覆盖
    step: Annotated[int, operator.add]        # ← 累加：节点返回 {"step": 1} 就 +1
```

| 字段类型 | 默认（无 reducer） | 加了 reducer |
|---|---|---|
| 消息历史 | ❌ 每轮被覆盖 | ✅ `add_messages` 追加 |
| 计数器 | ❌ 覆盖 | ✅ `operator.add` 递增 |

**新手最常踩的坑**：忘了给列表字段加 reducer，结果消息历史每轮被清空，模型「失忆」。

> 对应到你的代码：`state` 是裸 `Map`，**默认就是覆盖**。所以 `polish` 写 `answer` 时，会把 `faq` 写的 `answer` 覆盖掉 —— 靠的是 `obs.isEmpty()` 这个 `if` 手动处理（`WorkflowAgent.java:121-123`）。LangGraph 会把这件事交给 reducer。

### 5.4 「不是 DAG」—— 这是 LangChain 三年里最重要的一课

2026-07 LangChain 发了篇复盘：[3 Years of Graph Engineering with LangGraph](https://www.langchain.com/blog/3-years-of-graph-engineering-with-langgraph)。里面最关键的一条：

> **Agent graphs are usually not DAGs.**（智能体的图**通常不是**有向无环图）

为什么？因为生产里的 agent 需要**环**：

- 工具调用失败 → **重试**
- 信息不够 → **回头追问用户**
- 校验没过 → **改一版再来**
- 人还没批 → **暂停，等人**

DAG（有向无环图）表达不了这些，只能靠「复制节点」「预埋重试逻辑」硬凑 —— **LangGraph 就是当年被 DAG 框架的天花板逼出来的。**

> **「循环」不是图的例外，循环是图的特例。**（A loop is just a simple graph.）
> 你那份代码的图**没有环** —— 它是个 DAG，每个节点最多走一次。

### 5.5 执行模型：super-step（借自 Google Pregel）

LangGraph 的执行**不是**简单的「一个节点接一个节点」，而是受 [Google Pregel](https://research.google/pubs/pregel-a-system-for-large-scale-graph-processing/) 的 **BSP（Bulk Synchronous Parallel）** 模型启发，按 **super-step** 推进：

```
每个 super-step：
  ① 并行执行所有"就绪"的节点
  ② 收集它们的所有写操作
  ③ 用 reducer 原子地合并成新状态
  ④ 存一个检查点（checkpoint）
  ……重复，直到 END 或超过最大迭代
```

**关键约束**：某个节点的**所有前驱**必须已经完成并存了检查点，它才能开始。这避免了「前面还没跑完，后面就抢跑」的竞态。

你的代码里是**严格顺序**的 `runNode(...)` 连调 —— 教学版，没有并行这一步。

### 5.6 Checkpointer：状态快照变成一等公民

**每个 super-step 存一个状态快照**，用 `thread_id` 隔离不同会话。这一个机制，直接长出了五种能力：

| 能力 | 怎么来的 |
|---|---|
| **崩溃恢复** | 挂了？从最后一个检查点重放 |
| **断点续跑** | 人工审批 → 进程可以退出 → 批完再恢复 |
| **人工介入** | `interrupt()` 挂起 + `Command(resume=...)` 续跑 |
| **时间旅行调试** | 回到任意历史状态，看看当时发生了什么 |
| **跨会话记忆** | `thread_id` 相同 = 同一段对话 |

**「人工审批」的流程值得单独说**，因为它是 Checkpointer 最刚需的场景：

```python
graph.compile(checkpointer=PostgresSaver(conn))

graph.invoke(input, config)              # 跑到 interrupt() 停下
# ……可能几分钟后、可能另一个进程、可能另一个部署……
graph.invoke(Command(resume={"approved": True}), config)   # 从断点接着跑
```

**人的等待不占着服务器** —— 状态在 Postgres 里躺着，恢复时可以换个进程。

> **这就是为什么「可控性」不只是「路由写死」，还包括「随时能停下来、能接着跑」。** ReAct 的自由循环做不到这一点 —— 因为它压根没有「状态快照」这个概念。

### 5.7 「Own Your Control Flow」

LangChain 复盘里给出的操作性建议，一句话概括：

> **让模型的判断「显式、可测试」，而不是藏在 LLM 循环里。**

具体做法：

```python
# ✅ 模型只负责分类，路由用代码写清楚 —— 显式、可测
intent = llm.classify(query)          # 模型干模型的事
if intent == "refund":                # 代码干代码的事
    handle_refund()
elif intent == "question":
    answer()
```

对比 ReAct 那种「模型自己决定调哪个工具、什么时候停」—— 后者**没法写单测**，因为每次跑可能不一样。

> **这正是你 `WorkflowAgent` 里 `classify` 节点在做的事**（`WorkflowAgent.java:76-88`）：**模型只吐一个 `{"next": "..."}`，路由由代码执行。**

---

## 六、这份代码 ↔ LangGraph，同一个图，两种写法

把你的图用 LangGraph 写出来，对照最清楚：

**你的（命令式）：**

```java
runNode("classify", state);
String next = String.valueOf(state.getOrDefault("next", "faq"));
runNode(next, state);
runNode("polish", state);
```

**LangGraph（声明式）：**

```python
g = StateGraph(State)
g.add_node("classify", classify)
g.add_node("tool", tool)
g.add_node("faq", faq)
g.add_node("polish", polish)

g.add_edge(START, "classify")
g.add_conditional_edges("classify", route, {"tool": "tool", "faq": "faq"})  # ← 条件边
g.add_edge("tool", "polish")     # ← 汇合
g.add_edge("faq", "polish")      # ← 汇合
g.add_edge("polish", END)

app = g.compile(checkpointer=PostgresSaver(conn))
```

**同一个图，两种表达。** 差别在哪？

| | 命令式（你的） | 声明式（LangGraph） |
|---|---|---|
| 图在哪 | 藏在 `if` 和 `switch` 里 | **显式声明出来** |
| 能画出来吗 | 得脑补 | **能，它就是一段数据** |
| 能加持久化吗 | 得自己写 | `compile(checkpointer=...)` 一行 |
| 能并行吗 | 得自己写线程 | 图结构自带 |

> **声明式的价值不在「少写几行」，在「图本身成了一个可以被检查、被绘制、被测试的对象」。**

---

## 七、那么，什么时候**该**用图，什么时候**不该**

这是查资料收获最大的一块，也是很多教程不讲的部分。

### 7.1 Anthropic 的默认建议：**能简单就简单**

Anthropic 那篇的落脚是一句很硬的话：

> **Find the simplest solution that works, and only add complexity when it demonstrably pays for itself.**
> （找能跑通的最简方案，**只在复杂度被证明值得时**才加）

因为：

| | Workflow（图） | Agent（自由循环） |
|---|---|---|
| 成本 | 低 | 高 |
| 速度 | 快 | 慢 |
| 调试 | 容易 | 难 |
| 错误 | 不累积 | **会累积**（一步错，步步错） |

### 7.2 LangChain 三年的结论：**分场景**

| 场景 | 用什么 | 例子 |
|---|---|---|
| **结构化、可预测** | ✅ **图** | 客服分流、编码 agent、合规审批 |
| **开放式、步骤难以预测** | ❌ 用 **agent harness** | 深度调研 |

**而且有个很诚实的反例**：LangChain 自己**把早期的深度调研，从预定义图改回了 agentic 循环**（GPT Researcher 也做了同样的切换）—— 因为调研要「规划、委派、搜索、综合」的方式**事先根本定不下来**。

> **这句话很值钱：图的作者自己承认，有些任务不该用图。**
> 强行给「开放式任务」套确定路径，就是那句告诫 —— **「想要既完全动态、又完全可控，最后两头不占」。**

### 7.3 一个数据点：它有多主流

「graph engineering」这个词在 2026-07 被 Peter Steinberger 一条推文带火（问「行业是不是从 loop 转向 graph 了」）。LangChain 回应：**不新，LangGraph 已经跑了两三年，月下载 65M+。**

不过也有批评声音值得知道：2026-02 有一篇 [Diagrid 的博文](https://www.diagrid.io/blog/checkpoints-are-not-durable-execution-why-langgraph-crewai-google-adk-and-others-fall-short-for-production-agent-workflows) 指出，**checkpoint ≠ durable execution** —— LangGraph 的检查点只是「存档点」，没有自动故障检测、没有自动恢复、没有防重复执行、没有分布式调度。**「保存好状态，剩下你自己来。」**

> 这提醒：**LangGraph 的持久化是「够用级」，不是「生产级 durable execution」。** 但它把「状态可见、可恢复」这件事**变得可能** —— 这已经是质变。

---

## 落脚：回到这份代码

155 行，骨架三段：

```text
chat()
  ├─ ① State 初始化        LinkedHashMap          (46-49)
  ├─ ② classify（固定入口） runNode("classify")    (52)
  ├─ ③ 条件边              next = state["next"]    (55-61)
  ├─ ④ 走分支              runNode(next)           (61)
  ├─ ⑤ 汇合                runNode("polish")       (62)
  └─ ⑥ 返回 state["answer"]                        (68)
```

几个锚点：

| 位置 | 是什么 |
|---|---|
| `WorkflowAgent.java:46` | `state` —— **整个工作流唯一的共享内存**，一个 `Map` |
| `WorkflowAgent.java:55` | `state.get("next")` —— **条件边的判断依据**，模型写的 |
| `WorkflowAgent.java:72` | `runNode(node, state)` —— **节点执行器的统一签名**：读 state、写 state |
| `WorkflowAgent.java:121-123` | `obs.isEmpty() ? answer : "根据查询结果：" + obs` —— **手动 reducer**：两个分支的产物在这里合并 |

注意这里**没有**任何「编排引擎」「持久化」「并行调度」——

所谓「图」是**一个 `switch`**；
所谓「节点」是**一个 `case`**；
所谓「条件边」是**一个 `if`**；
所谓「状态」是**一个 `Map`**。

### 读这份代码会撞上的一个 Java 语法点：`switch` 的箭头形式

```java
// WorkflowAgent.java:75
switch (node) {
    case "classify" -> { ... }
    case "tool"     -> { ... }
    ...
}
```

**箭头形式的 `case` 天然不穿透，不需要 `break`。**

```java
switch (node) {
    case "classify":        // ← 冒号形式：会穿透，必须 break
        ...
        break;
    case "classify" -> ...  // ← 箭头形式：到此为止，不用 break
}
```

老式 `case X:` 忘了写 `break` 会直接掉进下一个分支，而且编译器不提醒 —— 箭头形式（Java 14+）就是来堵这个坑的。

顺带把同一个文件里的第三个形态也认了（`WorkflowAgent.java:95-101`）：

```java
double r = switch (m.group(2)) {
    case "+" -> a + b;          // 箭头 + 单个表达式 → 表达式本身就是值，不用 yield
    case "-" -> a - b;
    default -> Double.NaN;
};
```

| 形式 | 返回值怎么给 |
|---|---|
| 箭头 + 单表达式 | 表达式本身就是值，**不用 `yield`** |
| 箭头 + 代码块 `{}` | **必须 `yield`** |
| 冒号形式（用在表达式里） | 也是 **`yield`** |

**读这个文件的价值，不是学「怎么搭工作流」，而是看清一个思想：**

> **把控制流从模型的脑子里，搬到代码里，画成一张能被看见的图。**

---

## 一句话收束

> **ReAct 让模型决定「走哪步」，Step16 把这句话收回来，只给它「这一步说什么」的自由。**
> **图的全部价值，是让「怎么走」变成显式的、可画的、可测的、可存的。**

配套对照：

| 问题 | 答案 |
|---|---|
| 为什么用图不用线？ | 线表达不了**分支、汇合、循环** |
| 谁决定下一步？ | **代码**（边）；模型只决定**节点内部** |
| 节点是什么？ | 一段**能力**：读 state、写 state |
| 状态怎么合并？ | 这里**直接覆盖**；LangGraph 用 **Reducer** |
| 为什么需要持久化？ | 崩溃恢复、人工审批、断点续跑、时间回溯 |
| 什么时候不该用？ | **开放式任务**（如深度调研）—— 图作者自己就这么说 |

---

## 附一：一脉三兄弟（Step12 / 13 / 16）

| | Step12 多智能体 | Step13 子代理 | **Step16 工作流** |
|---|---|---|---|
| 控制流 | 写死的**线** | 模型决定 | 写死的**图**（有分支汇合） |
| 单元是 | 角色（人设） | 派遣对象 | **能力节点** |
| 谁在决定 | 代码 | 模型 | **代码定图 + 模型定节点内** |
| 能持久化吗 | 不能 | 不能 | 这里不能，**LangGraph 能** |
| 类比 | 流水线工厂 | 派活给同事 | **带红绿灯的公路** |

三者的坐标，一句话：

```
自由 ◀──────────────────────────────▶ 可控
ReAct          Step16 工作流        Step12 写死流水线
(模型定一切)    (代码定路径，          (代码定一切)
                模型定内容)
```

---

## 附二：和 AgentScope 对照（另一个同思路的框架）

LangGraph 不是这条路上唯一的方案。**阿里通义实验室的 AgentScope** 是另一个，而且它有几处正好补上这篇的两块空白：

| 这篇讲的 | AgentScope 里对应什么 |
|---|---|
| State（裸 `Map`） | `AgentState` —— **类型化状态**（补上「状态该有 schema」） |
| Checkpointer | Workspace + `AbstractFilesystem`（本地 / Redis / OSS / 沙箱一套接口） |
| 节点内部的模型循环 | **HarnessAgent** —— 把 ReAct 循环 + 上下文压缩 + 权限 + 沙箱包成一层 |
| 这里没有的「环」 | subagent 编排（动态派遣、同步/异步两种模式） |
| 「何时用图」的取舍 | 它把两条路都收进来：**图编排** + **agent harness** |

**它跟这篇最相关的一点**：AgentScope 2.0 主打的 **Harness 工程化层**，和 ToyAgent 第 15-22 个场景参考的 **deepseek-harness-java**，是同一个提法 ——

> **智能体 = 模型 + Harness。**

**Java 开发者注意**：AgentScope 的 Java 版是 **Spring AI Alibaba 的核心运行时**，如果你走 Java 技术栈，它比 LangGraph 更贴。但它**更年轻、学习曲线更陡**，概念比 LangGraph 多。**建议：LangGraph 读概念（资料最多），AgentScope 看 Java 落地。**

---

## 参考来源

**LangGraph 设计**
- [3 Years of Graph Engineering with LangGraph（LangChain, 2026-07-22）](https://www.langchain.com/blog/3-years-of-graph-engineering-with-langgraph) —— 「agent graphs are usually not DAGs」「Own Your Control Flow」、65M+ 月下载
- [LangGraph Overview（官方文档）](https://docs.langchain.com/oss/python/langgraph/overview) —— Node / Edge / State 三件套
- [StateGraph API（LangGraph 文档）](https://mintlify.wiki/langchain-ai/langgraph/api/state-graph) —— Reducer、类型化状态、partial update
- [Checkpointers（LangGraph 文档）](https://docs.langchain.com/oss/python/langgraph/checkpointers) —— 持久化、thread_id、durability 模式
- [Interrupts（LangGraph 文档）](https://docs.langchain.com/oss/python/langgraph/interrupts) —— 人工介入挂起/续跑
- [LangGraph 架构全解：图编排运行时的状态检查点与持久化设计（腾讯云）](https://cloud.tencent.cn/developer/article/2748338) —— super-step / Pregel / 原子状态更新
- [LangGraph 三年图工程实践（中文翻译）](https://github.com/oneRede/harness-engineering/blob/main/works/langchain-langgraph-3-years-translation.md)

**范式之争**
- [Building Effective Agents（Anthropic, 2024-12）](https://www.anthropic.com/engineering/building-effective-agents) —— Workflow 与 Agent 的经典定义、五种工作流模式、「能简单就简单」
- [Checkpoints Are Not Durable Execution（Diagrid, 2026-02）](https://www.diagrid.io/blog/checkpoints-are-not-durable-execution-why-langgraph-crewai-google-adk-and-others-fall-short-for-production-agent-workflows) —— 对 LangGraph 持久化能力的批评

**另一条路线**
- [AgentScope Java v2 官方介绍](https://java.agentscope.io/v2/zh/intro) —— Harness 层、Workspace、subagent 编排
- [AgentScope 2.0 生产可用，企业级 Harness 底座全解析（阿里云开发者）](https://developer.aliyun.com/article/1749475)
