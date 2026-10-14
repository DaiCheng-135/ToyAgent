# A2A 协作：从一个抬杠开始 —— 到底什么叫「不硬编码」？

> **学习札记 · ToyAgent Step14**
> 配套代码：[`A2AAgent.java`](./A2AAgent.java)
> 阅读前提：知道 `Agent` 接口只有 `chat(String)` 一个方法，知道「工具调用 = 模型吐 JSON、代码执行、结果塞回去」这个循环。

---

## 缘起

看完 Step14 的 `A2AAgent`，我的第一反应是一句抬杠：

> **「就是调用别人的 agent 吧，没啥出奇的地方，为什么没有专门的协议呢？」**

这句话被顶回来三次，最后逼出了三个真正有价值的问题：

1. 真的没有专门协议吗？
2. 那 REST 为什么不够用？
3. `GET https://对方域名/.well-known/agent.json` —— **这不还是得先知道域名吗？那到底什么叫「不硬编码」？？**

第三问把我问住了。这篇博客就是这三问三答的记录，**重点在第三问**。

---

## 第一问：真的没有专门协议吗？

有。而且名字就叫 **A2A**。

| 时间 | 事件 |
|---|---|
| 2025-04 | Google 发起 Agent2Agent (A2A) 协议 |
| 2025-06 | 捐给 Linux Foundation（创始成员：AWS、Cisco、Microsoft、Salesforce、SAP、ServiceNow） |
| 2025-08 | IBM 的竞争协议 ACP 并入 A2A |
| 2026-03 | 发布 **v1.0 稳定版**，加入签名名片（JWS 身份验证）、多协议绑定 |
| 2026-04 | 满一年：**150+ 家支持组织**，生产环境落地（供应链/金融/保险/IT 运维），GitHub 22,000+ star，官方 SDK 覆盖 Java/Python/JS/Go/.NET |
| 2026-08 | 并入 **AAIF**（Agentic AI Foundation），与 MCP 同属一个治理体系 |

所以「没啥出奇」这个判断，不少人也这么想 —— 但产业界显然不这么想。

**一句话区分 A2A 和 MCP：**

- **MCP**（Anthropic，2024-11）：**垂直** —— 一个 agent 够到工具和数据。
- **A2A**（Google，2025-04）：**水平** —— agent 和 agent 之间互相发现、委派任务。

用一句流行的话说：**agent 需要手（MCP），也需要同事（A2A）。**

---

## 第二问：REST 为什么不够？

我的直觉对了一半：**如果两端都是你自己写的、你控制双方，那 REST + 一个任务队列就够了，A2A 纯属多余开销。**

**协议只在「你不控制对方」时才值钱。这是分水岭。**

那 REST 到底哪里不够？你脑子里默认的那句「我知道对方是谁、我知道该问什么、我同步等它答完」，恰好是 REST 白送的三个假设 —— 而在 agent 场景里，三个全碎：

| REST 默认成立 | 调别人家 agent 时 | 协议要补什么 |
|---|---|---|
| 你**预先知道** endpoint 存在 | 你不知道网上有谁、谁擅长啥 | **Agent Card 名片 + 发现** |
| 契约**固定**，schema 提前写死 | 请求是一句自然语言任务，还可能来回澄清 | 统一的**任务信封**（task 描述 + task_id） |
| **同步** request/response，秒回 | 任务可能跑几分钟、能暂停/恢复/取消 | **task 状态机** submitted → working → completed |

第三点最要命：**REST 里没有「工作进行中」这个概念。** 你不能发一个 HTTP 请求，让它在对方那边挂 5 分钟还能中途报进度 —— 这不是 REST 的用法。

**统一约定值钱在哪？** 这是所有协议共用的同一套算术：

> **N 个东西两两对接 = N² 套适配；有了统一协议 = N 套。**

MCP 官方说法：5 个 agent 接 10 个工具，没协议最多 50 套定制集成，有了 MCP 塌成 1 个接口。A2A 是同一件事的横向版。

**所以协议不让「调用」变得可能，它让调用变得可组合。**

---

## 第三问（最深）：什么叫「不硬编码」？

### 3.1 先认错：这份教学代码里，地址就是硬编码的

`A2AAgent.java:49`

```java
private static final Map<String, AgentCard> DIRECTORY = new LinkedHashMap<>(Map.of(
        "translator", new AgentCard("translator", "http://a2a.local/translator", "1.0.0",
                List.of("translate"), "中英互译代理"),
        "weather", new AgentCard("weather", "http://a2a.local/weather", "2.1.0",
                List.of("forecast"), "天气预报代理")));
```

`http://a2a.local/translator` 就在这儿躺着，白纸黑字。

**所以「发起方从头到尾没有硬编码过对方的地址」这句话，在这份代码里根本不成立。** 我前面是拿真实 A2A 的性质去描述教学代码 —— 这是说岔的根源。

### 3.2 那「发现」到底省掉了什么？

拆成两件事，之前一直被我搅成一团：

**① 域名你还是得知道 —— 这个没省。**

`.well-known/agent.json` 只是**路径固定**，域名照样得你提供。**就像你还是得知道对方电话号码。**

省掉的**不是**「知道它存在」，是「知道它内部长什么样」。

**② 省掉的是：你不用知道对方「能干啥 + 接口怎么调」。**

同一件「让它翻译」的事：

| | 你**提前**必须知道的东西 |
|---|---|
| REST | 域名 + 路径 `/v2/translate` + 请求体 `{"text","from","to"}` + 字段名 |
| A2A | **域名**。就这一样 |

路径永远 `/.well-known/agent.json`，返回永远同一套结构。对方明天上线「图片翻译」—— 你**一行代码不改**，下次拉名片就看见了。REST 里对方加功能，你得重读文档、改请求体、重新部署。

### 3.3 所以「不硬编码」精确指什么？

**指能力，不是地址。**

```java
// ❌ 硬编码「能力」：加一个新 agent，必须改这里，重新部署
if (task 是翻译)   派给 translator;
else if (task 是天气) 派给 weather;
```

```java
// ✅ 不硬编码「能力」：拉名片，看谁的 skills 里有 translate，就派给谁
discover();   // translator: skills=[translate]   weather: skills=[forecast]
派给 skills 匹配上的那个;
```

**差别就一句话：加一个新 agent，上面那版要改代码重新部署，下面那版不用。**

这才是「发现」的全部价值 —— 不是「凭空知道有谁」，是「**知道有谁之后，不用提前知道他会什么**」。

### 3.4 最诚实的一段：引导（Bootstrap）问题

我的追问是：「我如果连该调哪家都不知道，也不知道域名，怎么办？」

**答案是：没有魔法。你必须有一个起点，不能是零。**

你只要联网，就一定得**预先知道某样东西**：

- 上网 → 你得知道 DNS 服务器 IP（这个 IP 是运营商配给你的，写死在你机器里）
- 发快递 → 你得知道「菜鸟」或「顺丰」这个入口存在
- **A2A → 你得知道一个「注册中心」的域名**

**这个「预先得知道的一点点」，叫引导（bootstrap）问题。所有网络协议都有，无解，也不需要解。**

A2A 唯一能做的，是让这个起点**薄到只有一行**：

```java
// 你代码里唯一写死的东西
String REGISTRY = "https://registry.a2a.org";
```

**除了这一行，别的都不写死。**

### 3.5 三个「不知道」，分别谁来解决

以「帮我订明天去上海的高铁」为例：

```
① LLM 拆解任务              ← 【模型】干，不是协议
   需要：查车次、下单、支付

② 问注册中心：谁会查车次？     ← 【协议】干
   GET https://registry.a2a.org/search?skill=query_train
   ↓ 返回一批名片（你事先一个都不认识）：
   [ 12306代理@rail.cn   评价4.9  会:query_train,book_train ]
   [ 携程代理@ctrip.com  评价4.7  会:query_train            ]

③ LLM 挑一个                 ← 【模型】干（按评价/价格/信任分）
   选定 12306代理

④ 拿域名拉详细名片            ← 【协议】
   GET https://rail.cn/.well-known/agent.json

⑤ 发任务，拿 task_id          ← 【协议】
   POST 任务 → {"task_id":"task-001","status":"working"}

⑥ 异步等回执                 ← 【协议】
```

| 你不知道的 | 谁来补 |
|---|---|
| 不知道**该调哪家** | **LLM** 看注册中心返回的候选名单，自己挑 |
| 不知道**哪个操作对应哪家** | **LLM** 运行时拆解任务 + 按 skill 搜索 |
| 不知道**域名** | **注册中心**告诉你 |
| **不知道注册中心在哪** | ❌ **没人能帮你。这行必须你写死。** |

**最后一行就是全部答案。「没有魔法」就指这个。**

### 3.6 更诚实的现实：开放发现到 2026 年仍是最弱一环

Linux Foundation 自己列的 A2A 未解决问题清单：

> *Trust, decentralized identity, registry, validation tooling... remain open roadmap items rather than solved problems.*
> （信任、去中心化身份、**注册中心**、验证工具 —— 这些仍是路线图上的未解项，不是已解决的问题）

评论说得更直白：**registries, identity, and trust scoring... discovery being where both are still weakest.**

意思是：**「全世界 agent 互相自动发现」这套乌托邦，今天还没实现。** 那 150 家采用 A2A 的组织，绝大多数是 **点对点、双方事先谈好的对接**（合同里给你域名，或内网运维系统里注册的），不是「搜一下随便找个陌生人」。

**所以一个 agent 站在那儿想问「我该调哪家」，真实世界的答案是：你得先有一个注册中心，而注册中心得有人给你。这一环今天靠人，不靠协议。**

### 3.7 那么，注册中心到底有哪些？（2026 现状）

追到底就该问了：既然「注册中心得有人给你」，那市面上到底有哪些？

**答案是：有，而且不止一个 —— 但没有一个「说了算的」。** 这恰恰就是 3.6 说的「最弱一环」的具体长相。

#### ① 直接索引 A2A 名片（社区 / 开源）

| 名字 | 特点 |
|---|---|
| **a2aregistry.org** | 社区驱动、开源。发 `/.well-known/agent-card.json` + 调一次 `POST /api/agents/register` 即可登记，每 30 分钟健康检查。有网页 UI、REST API、Python 客户端，还有自己的 MCP server |
| **@a2a-relay/registry** | npm 包。自动从 well-known URL 抓名片，支持全文 / 标签 / skill 搜索、周期健康检查 |
| **oo-a2a-registry** | PyPI。**心跳式注册**，同时兼容两代 A2A —— v0.3 用 `agent.json`，v1.0 用 `agent-card.json` |
| **Apicurio Registry** | 老牌 schema registry 转型（2026-02），把 `AGENT_CARD` 当**一等公民**做版本管理 + JSON Schema 校验 |
| **OpenAgora** | 带健康检查 + 支付方案，给 LangChain / CrewAI / Agno 提供 discover / call 工具 |

#### ② 企业级注册中心（内网发现）

| 名字 | 特点 |
|---|---|
| **Microsoft Entra Agent ID** | 身份层注册，学术论文里被列为五大方案之一 |
| **Azure API Center / AI Foundry** | 微软官方教程的推荐姿势：AI Foundry 托管 agent，Cosmos DB 当中心注册表，启动时自注册 + TTL 过期 + 心跳 |
| **Google Cloud / AWS Bedrock AgentCore** | 各自云内的 agent 目录 |

#### ③ 更大的一类：身份 / 网络层注册（不止管 A2A）

| 名字 | 特点 |
|---|---|
| **AgentVerse**（Fetch.ai） | **数量最大的一个** —— 2026-03 快照 36,338 个 agent，占 34.8% |
| **AGNTCY Agent Directory Service**（Cisco 主导） | OASF 能力模型 + OCI 存储 + IPFS DHT 寻址 + **Sigstore 签名**，防注册表投毒 |
| **NANDA Index**（MIT） | AgentFacts + W3C 可验证凭证，端点可轮换、可秒级吊销 |
| **ERC-8004**（以太坊链上） | 18,344 个 |
| **PulseMCP** | MCP 侧，16,510 个 |

#### ④ 两个必须知道的残酷事实

**残酷一：它们互相不通。** 2026 年 3 月有人统计：**15+ 个注册中心，合计 104,504 个 agent，彼此零互操作** —— 被形容成「**DNS 出现之前的 Web**」。同一个 agent 挂在几个注册表里，不是同一个连续身份。

**残酷二：真实使用率小得可怜。** API Evangelist 2026-09 拿 27,840 个 provider 做探测：

- 4,718 个探了 `/.well-known/agent-card.json`
- 1,760 个返回 2xx —— **但其中 1,402 个根本没在发名片**（SPA 壳子的软 404 返回了个 `text/html`）
- 真正能解析的名片：**只有 358 个**，全部来源加起来 **421 个，约占 1.5%**

那句话很狠：**「A 200 is not a card. A card is a card.」**（返回 200 不等于有名片。）如果按 HTTP 状态码统计，数字会虚高四倍。

#### ⑤ 顺带一个版本坑

前面几节写的都是 `/.well-known/agent.json` —— 那是 **A2A v0.3** 的路径。**v1.0 改成了 `/.well-known/agent-card.json`**。目前两边都还认，所以 `oo-a2a-registry` 这类注册中心要同时兼容两套。

> **小结：3.6 说「注册中心得有人给你」，3.7 告诉你——给你的人有三个阵营（社区 / 企业 / 身份网络），但他们谁都不理谁。这不是 A2A 没做好，是整个行业还没走到那一步。**

---

## 最后理清：黄页 → 名片 → 请求，到底几步？

最初的困惑是「我要先找黄页，再找名片，再用名片请求对方吗？」

**方向对，但有两处修正：**

**修正一：黄页和名片经常是同一个东西。** 注册中心里存的本来就是名片。搜一次，拿回来的**直接就是名片**。**所以实际是两步，不是三步：`搜黄页（= 拿到名片） → 照名片发`。**

**修正二：很多时候你压根不需要黄页。**

```
我要调某个能力
    │
    ├── 知道对方域名吗？
    │       ├── 知道 → 直接 GET https://对方域名/.well-known/agent.json
    │       └── 不知道 → 搜黄页，返回名片
    │
    └── 照名片上的 url 发任务
```

企业合作方给的、同事发的、合同里写的域名 —— 直接跳过黄页。

**名片上到底有什么？** 它是「**怎么调我**」的说明书：

| 字段 | 作用 |
|---|---|
| `name` | 我叫什么 |
| `url` | **往哪发** |
| `skills` | 我会什么（路由靠它） |
| `capabilities` | 我支不支持流式 / 异步推送 |
| `securitySchemes` | 怎么鉴权 |

**所以「用名片去请求对方」这句话字面上完全成立 —— 名片就是那张写着地址和密码的纸。**

---

## 落脚：回到这份代码

`DIRECTORY`（`A2AAgent.java:49`）是**黄页 + 名片库二合一** —— 它既扮演黄页（列出有哪些 agent），又存着名片本体（url、skills）。这是教学简化：真实里这两件事分属注册中心和对方域名下的 `/.well-known/agent.json`。

```text
discover()  →  翻黄页，拿到两张名片                    ← 对应上面的「找黄页」
send()      →  card = DIRECTORY.get(agent)   按名字取名片
               card.url()                    照名片上的地址发    ← 对应「照名片发」
```

**代码里看不到「两步走」，是因为黄页被写死成一个内存 Map 了。**

而 `handleRemotely()`（`A2AAgent.java:135`）是 `switch` 返回硬编码字符串 —— 真实里这里是 `POST` 请求。

**读这个文件的价值，不是学 HTTP 细节，而是看清一个思想：一旦代理要跨进程、跨主人，就必须先标准化「怎么找」（名片）和「怎么问」（信封）。**

---

## 一句话收束

> **A2A 不解决「我怎么知道有你」。**
> **它解决的是：「知道有你之后，我怎么在不看你文档的情况下，知道你能干啥、以及怎么等你回话。」**

配套的三行对照：

| 问题 | 答案 |
|---|---|
| 该调哪家？ | **LLM** 运行时路由 |
| 域名是多少？ | **注册中心**告诉你 |
| 注册中心在哪？ | **你必须预先知道** —— 引导问题，躲不掉 |

---

## 附：A2A vs 本仓库其他多代理场景

| | Step12 多智能体 | Step13 子代理 | **Step14 A2A** |
|---|---|---|---|
| 关系 | 规划-执行-审查 | **自家分身** | **外部同行** |
| 在哪 | 同进程 | 同进程，主代理原地 `new` | 网络另一端 |
| 怎么调 | 直接调方法 | 直接调方法 | 发请求，用 task_id 收结果 |
| 需要协议吗 | 不需要 | 不需要 | **需要**（名片 + 信封） |
| 类比 | 开会分工 | 叫同事来帮你 | **外包给合作公司** |

---

## 参考来源

- [A2A Protocol Surpasses 150 Organizations（Linux Foundation）](https://www.linuxfoundation.org/press/a2a-protocol-surpasses-150-organizations-lands-in-major-cloud-platforms-and-sees-enterprise-production-use-in-first-year)
- [A New Chapter for A2A: Joining the Agentic AI Foundation](https://a2a-protocol.org/latest/blog/2026/08/27/a-new-chapter-for-a2a-joining-the-agentic-ai-foundation/)
- [The Agent Communication Matrix: When MCP, A2A, and Plain REST Each Win（Oracle）](https://blogs.oracle.com/developers/the-agent-communication-matrix-when-mcp-a2a-and-plain-rest-each-win)
- [A Survey of Agent Interoperability Protocols（arXiv 2505.02279）](https://huggingface.co/papers/2505.02279)

**（3.7 注册中心生态）**

- [a2a-registry —— 社区 A2A 注册中心（GitHub）](https://github.com/prassanna-ravishankar/a2a-registry)
- [Apicurio Registry goes AI-Native: Introducing Agent Registry](https://www.apicur.io/blog/2026/02/05/apicurio-registry-ai-natural-evolution)
- [The agent registry landscape in 2026: 15+ ways for agents to find each other, none of them connected](https://blog.tobira.ai/agent-registry-landscape-2026/)
- [Evolution of AI Agent Registry Solutions: Centralized, Enterprise, and Distributed Approaches（arXiv 2508.03095）](https://arxiv.org/pdf/2508.03095v3)
- [A2A adoption is 421 of 27,840（API Evangelist）](https://raw.githubusercontent.com/api-evangelist/posts/main/_posts/2026-09-21-a2a-adoption-is-421-of-27840-and-most-of-them-arrived-with-the-card.md)
- [Design A2A Agent Ecosystems at Scale（Microsoft Learn）](https://learn.microsoft.com/zh-cn/training/modules/aaai-design-enterprise-agent-communication-a2a/2-design-agent-agent-ecosystems-scale)