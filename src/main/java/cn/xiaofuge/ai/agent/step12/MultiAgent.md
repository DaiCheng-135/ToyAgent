# 多智能体：一个模型戴了四顶帽子 —— 「团队」到底在哪？

> **学习札记 · ToyAgent Step12**
> 配套代码：[`MultiAgent.java`](./MultiAgent.java)
> 阅读前提：看过 Step05 的 MemoryAgent（`List<String>` 就是「记忆」），知道 `Agent` 接口只有 `chat(String)` 一个方法。

---

## 缘起

「多智能体协作」。**四个字听起来像开了四个 AI 在开会。**

看完 125 行，我愣住：

```java
private final ChatModel model;      // MultiAgent.java:26 —— 只有一个
```

四次 `model.chat()`，四段不同的 system prompt。**没有四个智能体，只有一个模型换了四次帽子。**

那「团队」到底在哪？「协作」又是什么？

这篇就是回答这两个问题。**重点在第二节** —— 协作的本质是什么。

---

## 一、先破误解：没有四个智能体，只有四段提示词

把「角色」摊开看，每个角色长这样：

```java
// 规划者（MultiAgent.java:43-48）
model.chat(List.of(Message.system("你是规划者（Planner）。把用户任务拆解为…"), Message.user(input)))

// 研究员（MultiAgent.java:57-59）
model.chat(List.of(Message.system("你是研究员（Researcher）…"), Message.user(input)))

// 写手（MultiAgent.java:63-68）
model.chat(List.of(Message.system("你是写手（Writer）…"), Message.user(input)))

// 审查员（MultiAgent.java:72-77）
model.chat(List.of(Message.system("你是审查员（Reviewer）…"), Message.user(draft)))
```

**同一个 `model`，四段 `Message.system(...)`。**

> **「角色」= 一段系统提示词。换提示词 = 换角色。**

这跟 Step13 子代理是**同一个真相**：

| | 「独立」在哪 | 「独立」在哪**不**在 |
|---|---|---|
| Step12 多智能体 | **提示词** | 进程、模型、实例 |
| Step13 子代理 | **上下文** | 进程、模型、实例 |

注意 `MultiAgent.java:19` 的注释自己也说了：「每个角色都是一个独立的 **ChatAgent 实例**（不同的系统提示词）」—— 括号里那半句才是实话。

---

## 二、「协作」的本质：一条硬编码的数据流

Javadoc 里说「协作的本质是：结构化的消息在角色之间流动」。

**翻译成代码，就是变量传参。**

```
  input
    │
    ▼
┌───────────┐
│ 🧭 Planner │──▶ plan           (MultiAgent.java:43)
└───────────┘
    │
    │  ⚠️ 下面这三个，用的都是原始 input，不是 plan
    ▼
┌────────────┐
│ 🔬 Researcher│──▶ notes        (MultiAgent.java:57)
└────────────┘
    │
    ▼   notes 传进去
┌───────────┐
│ ✍️ Writer  │──▶ draft          (MultiAgent.java:63)
└───────────┘
    │
    ▼   draft 传进去
┌─────────────┐
│ 📋 Reviewer │──▶ {pass, comment}  (MultiAgent.java:72)
└─────────────┘
    │
    ├── pass = true  ──────────────▶ draft 直接交付
    │
    └── pass = false ──▶ 重写一次 ──▶ 交付   (MultiAgent.java:87-91)
```

**所谓「消息在角色之间流动」，就是 `notes` 作为参数传给下一个 `model.chat()`。**

一条线，四段提示词，一个 if —— 就这些。

---

## 三、诚实的部分：这个 Planner 是「装饰性」的

这是我看第二遍才发现的，值得单独说：

```java
// 规划者产出了 plan（MultiAgent.java:49-54）
List<?> plan = planObj instanceof Map<?, ?> m ? (List<?>) m.get("plan") : List.of();
lastTrace.add(Map.of("type", "agent", "label", "🧭 规划者",
        "detail", "产出 " + plan.size() + " 步计划：" + describe(plan)));   // ← 只进了 trace

// 而研究员拿的是什么？（MultiAgent.java:57-59）
model.chat(List.of(Message.system("你是研究员…"), Message.user(input)));   // ← 是原始 input！
```

**`plan` 算出来了，但只进了右侧的轨迹面板 —— 从没参与后续执行。** 研究员、写手用的都是原始 `input`。

所以在这个教学版本里：**规划者是个「演示位」** —— 它展示了「先规划再执行」这个**模式**，但它的产物没有真正喂给下游。

> 真实的多智能体里，`plan` 应该被拆开分派给各个 worker（这正是 Anthropic 说的 **Orchestrator-Workers** 模式）。这份代码留了这个位置，但没接线。

**读教学代码时，分清「演示了模式」和「真的接上了」，很重要。**

---

## 四、这四个角色，正好对应两种经典 Workflow 模式

Anthropic 在 [Building Effective Agents](https://www.anthropic.com/engineering/building-effective-agents)（2024-12）里总结了五种工作流模式。这份代码里能对上两个：

| 这里的角色 | 对应模式 | 一句话 |
|---|---|---|
| 研究员 → 写手 | **Prompt Chaining**（提示链） | 一步的输出喂给下一步 |
| 写手 ⇄ 审查员 | **Evaluator-Optimizer**（评估-优化） | 一个生成，另一个挑刺，不过就重来 |

**Evaluator-Optimizer 那处最值得看**（`MultiAgent.java:87-91`）：

```java
String finalAnswer = pass
        ? draft                                              // 通过 → 直接交
        : model.chat(List.of(                                 // 打回 → 带着意见重写
                Message.system("根据审查意见修改短文，直接输出修改后的版本。"),
                Message.user("原文: " + draft + "\n审查意见: " + comment)));
```

**这是个真的反馈回路** —— 「生成 → 审查 → 不满意就带着意见重生成」。虽然只重试一次，但模式是完整的。

---

## 五、和 Step13 子代理，差在哪？

这俩最容易混，摆一起：

| | **Step12 多智能体** | **Step13 子代理** |
|---|---|---|
| 流程 | **写死的** 4 步流水线 | **动态的**，模型自己决定派不派 |
| 角色谁定 | **程序员**（代码里挨着写） | **模型**（运行时吐 JSON） |
| 数据怎么流 | `notes`、`draft` 变量传参 | 子代理只回一个 `String` |
| 加一个角色 | 改代码、重排队、重新部署 | 改一行 `CHILDREN` map |
| 谁在决定 | **代码** | **模型** |
| 类比 | **流水线工厂** | 派活给同事 |

一句话：

> **Step12 是「流程写死」，Step13 是「模型现场决定」。**

---

## 跑一遍：把「一个演员演四个角色」走完全程

### 先把这个比喻立起来

这篇最反直觉的地方是「一个模型四顶帽子」。用**一个演员**来理解：

| 代码里 | 现实里 |
|---|---|
| `model`（只有一个） | **一个演员** |
| 四段 `Message.system(...)` | **四套戏服 + 台词本** |
| 换角色 = 换提示词 | **换戏服就换角色** |
| `chat()` 里五个 `model.chat()` 的顺序 | **剧本的场次顺序**（第一幕、第二幕…） |
| `notes`、`draft` 这些变量 | **上一幕留给下一幕的道具** |

**关键：台上自始至终只有一个人。** 「四个智能体」是错觉 —— 是同一个演员换了四次装。

### 走一遍

**输入：`"介绍下杭州"`**

**第 1 幕 · 规划者（`MultiAgent.java:43-48`）**

```java
model.chat([system("你是规划者（Planner）。把用户任务拆解为三个角色的执行计划，只输出 JSON：..."),
            user("介绍下杭州")])
// 返回：{"plan": [{"role":"研究员","task":"查杭州资料"},
//                 {"role":"写手","task":"写简介"},
//                 {"role":"审查员","task":"审稿"}]}

List<?> plan = ...   // 3 步
lastTrace.add(... "🧭 规划者", "产出 3 步计划：研究员→写手→审查员")
// ★ plan 到此为止 —— 只进了轨迹面板，没传给任何人（见前面第三节）
```

**第 2 幕 · 研究员（`MultiAgent.java:57-59`）**

```java
model.chat([system("你是研究员（Researcher），围绕任务输出 3 条以内研究要点，每条一句话。"),
            user("介绍下杭州")])            // ← 用的是原始 input，不是 plan
// 返回 notes："杭州是浙江省会，有西湖与京杭大运河；数字经济发达；亚运会举办地。"
```

**第 3 幕 · 写手（`MultiAgent.java:63-68`）**

```java
model.chat([system("你是写手（Writer）。基于研究笔记写一段 100 字以内的短文，主题如下。
                   研究笔记: " + notes),
            user("介绍下杭州")])            // ← notes 在这里传进去（道具交接）
// 返回 draft："杭州，浙江省会，西湖与运河在此交汇……"
```

**第 4 幕 · 审查员（`MultiAgent.java:72-77`）**

```java
model.chat([system("你是审查员（Reviewer）。审查短文，只输出 JSON：{\"pass\": true/false, \"comment\": \"一句话点评\"}"),
            user(draft)])                   // ← draft 在这里传进去
// 返回：{"pass": true, "comment": "事实准确，表达流畅"}
```

**第 5 幕 · 交付（`MultiAgent.java:87-91`）**

```java
boolean pass = true
String finalAnswer = pass ? draft : model.chat([... 带着审查意见重写 ...]);
// pass = true → 直接交 draft
return finalAnswer;
```

**若第 4 幕返回 `{"pass": false, "comment": "缺少人口数据"}`** —— 走 else 分支，**演员又穿回写手的戏服，带着意见重写一遍**。这就是 Evaluator-Optimizer 的反馈回路。

### 道具是怎么传的（一眼看穿「协作」）

```text
input ──┬─▶ 🧭 Planner    → plan       （道具丢在原地，没用上）
        ├─▶ 🔬 Researcher → notes ────┐
        └─▶ ✍️ Writer  ◀─────────────┘  → draft ─┐
                                                  │
                                  📋 Reviewer ◀───┘  → pass?
                                                       │
                                        pass=true ─────┴───── pass=false
                                            │                     │
                                            ▼                     ▼
                                          交付             重写一次 → 交付
```

**所谓「消息在角色之间流动」，就是 `notes` 和 `draft` 这两个变量在往下传。**

---

## 落脚：回到这份代码

125 行，全在 `chat()` 一个方法里，摊平就是五段：

```text
chat()
  ├─ ① Planner    model.chat(...) → plan     (43-48)   ← 产物只进 trace
  ├─ ② Researcher model.chat(...) → notes    (57-59)
  ├─ ③ Writer     model.chat(...) → draft    (63-68)
  ├─ ④ Reviewer   model.chat(...) → pass?    (72-77)
  └─ ⑤ 汇总       pass ? draft : 重写一次     (87-91)
```

几个锚点：

| 位置 | 是什么 |
|---|---|
| `MultiAgent.java:26` | **唯一的 `model`** —— 四个角色共用它，这就是「一个模型四顶帽子」 |
| `MultiAgent.java:43-91` | 五个 `model.chat()` 挨着排 —— **流水线就是这几行的顺序** |
| `MultiAgent.java:50` | `plan` 解析结果，**只被 `describe()` 用了** |
| `MultiAgent.java:87-91` | 三元表达式里的**反馈回路**：通过就交，打回就重写 |

注意这里**没有**任何「调度器」「消息总线」「编排引擎」——

所谓「协作」是**变量传参**；
所谓「角色」是**一段 `system` 提示词**；
所谓「流程」是**五行的先后顺序**。

**读这个文件的价值，不是学「怎么搞多智能体」，而是看清一个思想：**

> **「多智能体」是个「提示词 + 数据流」概念，不是「多个模型」概念。**

---

## 一句话收束

> **不是四个 AI 在开会。**
> **是一个模型，戴四顶帽子，沿着一条写好的线走一遍。**
> 「团队」的全部内容，就是**四段 system prompt + 五个按顺序调用的 `model.chat()`**。

配套对照：

| 问题 | 答案 |
|---|---|
| 有几个智能体？ | **一个 `model`。** 四个角色是四段提示词 |
| 「协作」是什么？ | **变量传参** —— `notes` 传给写手，`draft` 传给审查员 |
| 谁决定流程？ | **程序员**（代码里的五行顺序） |
| 有反馈吗？ | **有，一处** —— 审查不通过就带着意见重写一次 |
| 和 Step13 差在哪？ | Step12 **流程写死**，Step13 **模型自己决定** |

---

## 附：一脉三兄弟（Step12 / 13 / 16）

| | **Step12 多智能体** | Step13 子代理 | Step16 工作流 |
|---|---|---|---|
| 控制流 | 写死的**线** | 模型决定 | 写死的**图**（有分支） |
| 单元是什么 | 角色（人设） | 派遣对象 | **能力节点**（分类/工具/问答） |
| 谁在决定 | 代码 | 模型 | 代码定图，模型定**节点内** |
| 类比 | 流水线工厂 | 派活给同事 | **带红绿灯的公路** |

---

## 参考来源

- [Building Effective Agents（Anthropic, 2024-12）](https://www.anthropic.com/engineering/building-effective-agents) —— 五种工作流模式（Prompt Chaining / Routing / Parallelization / Orchestrator-Workers / Evaluator-Optimizer）的出处
