# ReAct 运行时：从一个「这不就是往列表里加东西吗」开始 —— 循环撑爆了怎么办？

> **学习札记 · ToyAgent Step17**
> 配套代码：[`RuntimeAgent.java`](./RuntimeAgent.java) ／ [`TurnEndReason.java`](./TurnEndReason.java)
> 阅读前提：看过 Step03 的 ReAct 循环（think → act → observe）、Step11 的工具注册表、Step15 的循环守卫。

---

## 缘起

看完 Step03，我一度觉得自己把 ReAct 看穿了：

> **「所谓 ReAct 循环，不就是往 `history` 里不断追加，然后整个重发一遍吗？」**

对。**算法本身就这么点东西，小学生都能写。**

那问题来了 —— **既然这么简单，为什么还要单开一个 Step17？**

打开 `RuntimeAgent`，看到 `trimToBudget()` 那十几行，我才反应过来它处理的是什么：

> **它处理的不是"怎么写循环"，是"循环写出来之后，它自己会往哪儿坏"。**

这篇讲三件事，**重点在第三节**。

---

## 一、先立个比喻：Runtime = 客服接线员

| 代码里 | 现实里 |
|---|---|
| 一次 `chat(input)` | **一通来电**（接通 → 处理 → 挂断） |
| `for (step = 1..6)` | 通话中**一次一次地查资料** |
| `history`（`RuntimeAgent.java:47`） | **服务记录本**（跨来电累积 —— 同个客户可能再打来） |
| `registry` | 桌上的**工具书**（直接从 Step11 搬来的） |
| `SYSTEM_PROMPT` | 墙上贴的**标准话术**（永远在，不会被撕） |
| `trimToBudget()` | 记录本写满了，**撕掉最早几页** |
| `TurnEndReason` | **挂断原因** —— 答完了 / 查到上限了 / 系统故障 |
| `MAX_STEPS_PER_TURN = 6` | 一通电话最多查 6 次，超了就说「抱歉，我先挂」 |

**这个比喻最值钱的地方在「一通来电 vs 一次查资料」** —— 这就是 **turn / step 两级**。

---

## 二、机制一：turn / step 两级循环

```
chat("北京天气怎么样")          ← 一个 turn（一通来电）
  └─ runTurn()                  ← 循环在这里（RuntimeAgent.java:91）
       Step 1: 想 + 查天气        ← 一个 step
       Step 2: 想 + 给答案        ← 一个 step
     → return TurnEndReason        ← 必须带着一个"挂断原因"回来
```

### 2.1 定义：一个来回 = 一个 turn，一步 = 一个 step

- **turn（回合）** = 用户说一句、系统答一句，**这一个来回**。
- **step（步）** = 在这个来回内部，模型「想一步、做一步」算一步。

代码里的对应：

| | 代码里是哪个 |
|---|---|
| **一个 turn** | **`chat(input)` 被调用一次**（`RuntimeAgent.java:63`） |
| **一个 step** | **`for (step = 1; step <= 6)` 转一圈**（`RuntimeAgent.java:92`） |

### 2.2 看两个来回就清楚了

```text
用户：北京天气怎么样
  └─ turn 1
       step 1: 想 →「要查天气」→ 查了 → 得到 晴，26℃
       step 2: 想 →「够了」   → 答「北京今天晴，26℃」

用户：那上海呢
  └─ turn 2
       step 1: 想 →「要查天气」→ 查了 → 得到 多云，28℃
       step 2: 想 →「够了」   → 答「上海今天多云，28℃」
```

**2 个 turn，4 个 step。**

### 2.3 关系：1 个 turn = N 个 step

```text
turn  ┌────────────────────────────────┐
      │  step 1   step 2   step 3  ... │
      └────────────────────────────────┘
      N 是几，看模型要查几次
```

| 情况 | 几个 step |
|---|---|
| 模型**直接答** | **1 个**（想一步就够） |
| 模型**查 3 次才答** | **4 个** |
| 模型**死循环** | 撞 `MAX_STEPS_PER_TURN = 6` 才停 |

**turn 永远是 1 个**（你只问了一次）。**step 是几，模型说了算。**

### 2.4 为什么必须分开

因为**两个节奏不一样**：

| | 谁在控制 | 用来干什么 |
|---|---|---|
| **turn** | **用户**（你按一次回车 = 1 个） | 记忆按它存 —— `history` **跨 turn 累积** |
| **step** | **模型**（它想几步就几步） | 保险丝按它数 —— **单回合内最多 6 步** |

> **保险丝按 step 数**：要防的是「模型一个回合内转不停」。
> **记忆按 turn 存**：要记住的是「你和用户聊了几轮」。

**这两个数要是混成一个**，就会出现：防死循环时误伤了「用户问了太多轮」，或者记住对话时又被单回合的步数连累。**Step03 就是混着的。**

> 回到比喻：**turn = 一通来电，step = 通话中查一次资料。**
> 一通电话里可能查 0 次，也可能查 6 次 —— **但电话只有一通。**

---

## 三、机制二：`TurnEndReason` —— 把「为什么停」变成数据 ⭐

这是本步**最漂亮的一处**。

### 3.1 它是个 `sealed` 类型

```java
// TurnEndReason.java:18-27
public sealed interface TurnEndReason {
    record Completed(String summary) implements TurnEndReason { }   // 正常答完
    record MaxSteps(long steps)      implements TurnEndReason { }   // 撞保险丝
    record Error(String message)     implements TurnEndReason { }   // 异常终止
}
```

（出处：参考 deepseek-harness-java 的同名类型 —— `TurnEndReason.java:8`。）

**为什么不直接用 `enum`？** 因为三种结局**带的数据不一样**：

| 结局 | 自带什么 |
|---|---|
| `Completed` | 一句话说明（`summary`） |
| `MaxSteps` | 跑了几步（`steps`） |
| `Error` | 错误信息（`message`） |

`enum` 也能硬塞字段，但那会让「三种情况的数据」挤在同一个类里，谁跟谁对不上。
**`sealed interface` + 每种一个 `record` = 每种情况自带它需要的数据。**

`chat()` 拿到它之后，**用模式匹配逐一分派**（`RuntimeAgent.java:74-84`）：

```java
TurnEndReason reason = runTurn(input);

if (reason instanceof TurnEndReason.Completed c) {
    label = "Turn 结束 · COMPLETED —— " + c.summary();
} else if (reason instanceof TurnEndReason.MaxSteps m) {
    label = "Turn 结束 · MAX_STEPS —— 达到保险丝 " + m.steps() + " 步，强制收敛";
} else {
    label = "Turn 结束 · ERROR —— " + ((TurnEndReason.Error) reason).message();
}
```

### 3.2 它解决的到底是什么问题

```
Step03：循环结束了 —— 但为什么结束的？
        （是模型给答案了？还是轮数跑满了？得回头读代码猜）

Step17：循环结束 —— 一定带着一个 TurnEndReason
        （结束原因是数据，能攥在手里，能往下传）
```

**为什么这件事重要？** 因为下游需要它：

| 下游 | 要知道什么 |
|---|---|
| 前端展示 | 「这轮是正常答完，还是被截断了？」—— 用户有权知道 |
| 审计日志 | 「为什么停的？」 |
| 续跑决策 | 撞保险丝了 → 也许可以再放它跑一轮 |
| 调试 | 「它老是停，到底卡在哪一步？」 |

### 3.3 `sealed` 又加了一层保险

`sealed` 的价值在注释里写得很清楚（`TurnEndReason.java:15-16`）：

> **编译器穷尽检查 —— 新增一种结束原因时，所有 `switch` 它的地方都会被编译器提醒补分支。**

**俗称"忘了处理某种结局"的隐患，被编译器堵死了。**

**这个模式有正规名字：代数数据类型（Algebraic Data Type, ADT）** —— 「一组**封闭的**可能取值，每种取值自带不同的数据」。

| 语言 | 怎么实现 ADT |
|---|---|
| **Java** | `sealed interface` + `record`（本篇） |
| Kotlin / Scala | `sealed class` |
| Rust | `enum`（Rust 的 enum 天生带数据，本身就是 ADT） |

**「封闭」是关键**：值域是**有限的、可枚举的** —— 所以编译器才有可能做穷尽检查。要是 `TurnEndReason` 是个普通 `interface`，谁都能实现一个第四种结局，穷尽检查就废了。

---

## 四、机制三：上下文裁剪 —— 本步的重点 ⭐⭐

### 4.1 问题从哪来

回到开头那句：「ReAct 不就是往 `history` 里加东西吗？」

**对 —— 而问题恰恰就在"加"这个动作是永不停歇的。**

```
Step 1: history 加 2 条
Step 2: history 加 2 条
Step N: history 加 2 条
```

**没有上限。** 而每次调模型，都要把**整个 `history`** 发过去（`RuntimeAgent.java:94-100`）：

```java
List<Message> req = new ArrayList<>();
req.add(Message.system(SYSTEM_PROMPT + registry.promptCatalog() + "决策规则：..."));
history.forEach(m -> req.add(m));     // ← 整个 history，一条不落
```

于是：

| 第几轮 | 这轮要发多少 |
|---|---|
| 第 1 轮 | 1 句 |
| 第 2 轮 | 1+1 句（第 1 轮那句**重发**） |
| 第 50 轮 | 50 句 |

**总花费是 `1+2+3+…+50 ≈ 1275` 份，不是 50 份 —— 平方级增长。**

> **这就是为什么 agent 聊长了会突然又慢又贵。** 不是模型变笨了，是你要它读的东西变多了。

### 4.2 怎么裁

```java
// RuntimeAgent.java:160-172
private void trimToBudget() {
    if (estimateTokens(history) <= BUDGET_TOKENS || history.size() <= KEEP_MIN) return;
    int drop = 0;
    while (estimateTokens(history) > BUDGET_TOKENS && history.size() > KEEP_MIN) {
        history.remove(0);          // ← 从最老的开始丢
        drop++;
    }
    lastTrace.add(Map.of("type", "compress", "label", "上下文裁剪 truncateToBudget",
            "detail", "预估 token 超过预算 " + BUDGET_TOKENS + "，丢弃最早 " + drop
                    + " 条消息，保留最近 " + history.size() + " 条，现估 " + estimateTokens(history) + " tokens"));
}
```

三条规则：

| | 规则 | 为什么 |
|---|---|---|
| **怎么估** | `token ≈ 字符数 / 4`（`RuntimeAgent.java:156-158`） | 教学近似（dsh-java 同款） |
| **从哪丢** | **最老的**（`remove(0)`） | 最近的话最相关 |
| **留多少** | 至少 `KEEP_MIN = 4` 条 | 保底，别把上下文清空 |

### 4.3 ⭐ 一个精巧的设计：系统提示词**不在** `history` 里

看这两行（`RuntimeAgent.java:95-100`）：

```java
req.add(Message.system(SYSTEM_PROMPT + registry.promptCatalog() + "决策规则：..."));  // ← 现拼
history.forEach(m -> req.add(m));     // ← history 里全是 user / assistant
```

**`history` 里从头到尾就没有 system 消息。** 所以 `trimToBudget()` 再怎么丢，**永远丢不到系统提示词**。

要是系统提示词也塞进 `history`，裁剪时就得小心翼翼别把它删了 —— 现在这个结构**从根上杜绝了那个 bug**。

> **对应比喻：墙上的标准话术（system）和你的记录本（history）是两样东西。撕记录本，撕不到墙。**

### 4.4 代价要认

**模型会"忘掉"前面说过的事。**

> ⭐ **这就是为什么 agent 会「失忆」。**
> 不是它傻 —— 是有人把它最早说过的话**删掉了**。

Step05 MemoryAgent 整篇都在处理这件事；**Step17 的裁剪，是同一堵墙的另一个面。**

---

## 五、跑一遍

### 5.1 正常一次：`"北京天气怎么样"`

**① 接入（`RuntimeAgent.java:63-68`）**

```java
history.add(user("北京天气怎么样"))
trimToBudget()      // 估 ≈ 1 token，远没到 100，直接返回
reason = runTurn("北京天气怎么样")
```

**② Step 1**

```java
// 组装请求：系统提示词（现拼）+ 整个 history
req = [ system(你是 ReAct 智能体 + 工具清单 + 决策规则),
        user("北京天气怎么样") ]

raw = model.chat(req)
// 模型返回：{"thought": "需要查天气", "action": "get_weather", "action_input": "北京"}

thought = "需要查天气"      → trace "Step 1 · 思考"
fin     = null              → 不是终点
action  = "get_weather"     → trace "Step 1 · 行动"

observation = registry.lookup("get_weather")     // ← Step11 的注册表，直接复用
                      .map(t -> t.execute(Map.of("city", "北京", ...)))
                      .orElse("未知工具...")
            = "晴，26℃"
```

**往 history 追加两句（`RuntimeAgent.java:144-145`）**：

```java
history.add(assistant(raw));                          // 模型的原话
history.add(user("Observation: 晴，26℃"));            // 工具结果
trimToBudget()
```

```java
history = [ 用户: 北京天气怎么样,
            助手: {"thought":"需要查天气","action":"get_weather",...},
            用户: Observation: 晴，26℃ ]
// 估 ≈ 20 tokens，仍没超，不裁
```

**③ Step 2**

```java
req = [ system(...), ...整个 history（3 条） ]
raw = model.chat(req)
// 返回：{"thought": "信息够了", "final": "北京今天晴，气温 26℃。"}

fin = "北京今天晴，气温 26℃。"
→ lastAnswer = fin
→ history.add(assistant(raw))
→ return TurnEndReason.Completed("模型给出最终答案")
```

**④ 收尾（`RuntimeAgent.java:74-85`）**

```java
reason instanceof Completed → label = "Turn 结束 · COMPLETED —— 模型给出最终答案"
return lastAnswer;   // "北京今天晴，气温 26℃。"
```

**两句话一个 turn、两个 step、`Completed` 收场。**

### 5.2 看保险丝起跳：`"一直用 echo 回显 hello，不要停"`

```
Step 1: action=echo → observation="echo: hello"
Step 2: action=echo → observation="echo: hello"
Step 3: action=echo → observation="echo: hello"
Step 4: action=echo → observation="echo: hello"
Step 5: action=echo → observation="echo: hello"
Step 6: action=echo → observation="echo: hello"
── for (step = 1; step <= 6) 条件不成立 ──
lastAnswer = "已达最大步数 6，本回合被保险丝强制终止。..."
return TurnEndReason.MaxSteps(6)      ← 挂断原因：超限（RuntimeAgent.java:150）
```

（`EchoTool` 就是专为这个测试造的，`RuntimeAgent.java:224-232`。）

### 5.3 看裁剪触发

`BUDGET_TOKENS = 100` 是**教学调小的值**（真实系统是几万到几十万）。所以聊几轮就会在轨迹里看到这条：

```text
🗜️ 上下文裁剪 truncateToBudget
预估 token 超过预算 100，丢弃最早 3 条消息，保留最近 4 条，现估 96 tokens
```

**从这一刻起，模型就"看不见"最早那几句话了。** 它接下来可能答得没错 —— 因为最近 4 条还留着；也可能答得莫名其妙 —— 因为前因被删了。

**这两种结果，都是设计的正常表现。**

---

## 落脚：回到这份代码

264 行，骨架三段：

```text
chat()                         ← 一通来电
  ├─ history.add(user)              (65)
  ├─ trimToBudget()                 (68)   ← 裁剪①：进来先裁
  ├─ runTurn()                      (91)   ← 循环
  │    ├─ req = system + history    (94-100)
  │    ├─ model.chat(req)           (104)
  │    ├─ 有 final?  → Completed     (119-124)
  │    ├─ 有 action? → 执行工具       (127-141)
  │    │    └─ history 加 2 条
  │    │    └─ trimToBudget()       (146)  ← 裁剪②：每步之后也裁
  │    └─ 跑满 6 步 → MaxSteps       (148-150)
  └─ 按 TurnEndReason 分派收尾        (74-85)
```

几个锚点：

| 位置 | 是什么 |
|---|---|
| `RuntimeAgent.java:36-40` | `MAX_STEPS_PER_TURN = 6` / `BUDGET_TOKENS = 100` / `KEEP_MIN = 4` —— **三个旋钮就是全部策略** |
| `RuntimeAgent.java:47` | `history` —— **跨对话的会话历史，裁剪作用的就是它** |
| `RuntimeAgent.java:94-100` | 请求组装 —— **系统提示词现拼，不放进 history** |
| `RuntimeAgent.java:144-145` | 每步往 history 加两句 —— **"记忆增加"的本体** |
| `RuntimeAgent.java:160-172` | `trimToBudget()` —— **本步的重点** |

注意这里**没有**任何「向量数据库」「记忆压缩模型」——

所谓「上下文管理」是**一个 `while` 循环 + 一个估算函数**；
所谓「实时裁剪」是**每步之后顺手调一下**。

### 一个诚实的小瑕疵

```java
private String lastAnswer = "";    // ← 声明在 RuntimeAgent.java:88
                                   //    但 chat() 在 :85 就用了它
```

`runTurn()` 通过**给字段赋值**把答案"带出来"，`chat()` 再读这个字段。这能跑，但**是个坏味道**。

**正解**：让 `runTurn()` 直接返回一个记录，比如

```java
record TurnResult(String answer, TurnEndReason reason) { }
```

**教学代码里这不算错，但看得出来的人，就知道该往哪改。**

---

## 一句话收束

> **ReAct 循环的算法，是「往 history 里加东西、再整个重发」—— 小学生都能写。**
> **Step17 处理的，是这个动作**永不停歇**之后的两件事：**
> **① 怎么知道它为什么停（`TurnEndReason`） ② 怎么别让它撑爆（`trimToBudget`）**

配套对照：

| 问题 | 答案 |
|---|---|
| 一次 `chat()` 是什么？ | 一个 **turn**；内部循环是 **step** |
| 循环什么时候停？ | 三种之一：`Completed` / `MaxSteps` / `Error` |
| 「为什么停」怎么表达？ | **`sealed` 类型的 `TurnEndReason`** —— 编译器穷尽检查 |
| 上下文太长怎么办？ | 超预算就从**最老**的开始丢，保底留 4 条 |
| 为什么系统提示词裁不掉？ | **它压根不在 `history` 里**，每次现拼 |
| 代价是什么？ | **模型会"失忆"** —— 最早的话被删了 |

---

## 附：Step03 / 15 / 17 —— 循环的三个层次

| | Step03 ReAct | Step15 Loop 运行时 | **Step17 Runtime** |
|---|---|---|---|
| 关心什么 | **算法**：think-act-observe | **安全**：四道防线 | **资源 + 可观测** |
| 循环结构 | 一层（混着） | 一层 | **turn / step 两级** |
| 结束方式 | 跑满 `for` 就完 | 字符串判断 | **`TurnEndReason` 显式声明** |
| 上下文 | 不管，一直堆 | 不管 | **超预算自动裁剪** ⭐ |
| 工具 | 写死 `switch` | 写死 `switch` | **复用 Step11 注册表** |
| 类比 | 发动机 | 整车（刹车 / 气囊） | **整车 + 仪表盘 + 油箱** |

三者坐标一句话：

```
Step03  裸循环          —— 算法
Step15  守卫 + 保险丝    —— 别出事
Step17  裁剪 + 结束原因  —— 别撑爆、并且知道自己为什么停
```
