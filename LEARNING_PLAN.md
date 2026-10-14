# 30 天 AI Agent 学习计划

> **起点**：2026-10-15　**目标**：能拿出一个能讲的项目，开始投简历
> **每天约 2 小时**（全天投入可打对折）
> **路线**：AI 应用开发工程师（Java 后端 + AI 工程化）

---

## 铁律（违反了这计划就废了）

1. **每天必须有一行代码产出。** 连续两天只读不写 = 计划失败。
2. **卡住超过 30 分钟就跳过**，记下来，周末统一解决。
3. **跑通再读代码。** 顺序反了，理解速度差好几倍。
4. **第 30 天之后继续投，别停下来等"学完"。**

---

## 参考仓库

**AgentScope**
- 主仓（Java）：<https://github.com/agentscope-ai/agentscope-java>
- 示例集：<https://github.com/agentscope-ai/agentscope-samples>
- 文档：<https://docs.agentscope.io>
- ⚠️ 老仓库是 `modelscope/agentscope`，新版在 **`agentscope-ai`** 组织下，别 clone 错

**Spring AI**
- 主仓：<https://github.com/alibaba/spring-ai-alibaba>（`examples/deepresearch` 在这）
- 官方示例集：<https://github.com/spring-ai-alibaba/examples>
- Spring 官方：<https://github.com/spring-projects/spring-ai>
- 中文站点：<https://java2ai.com>

**社区实战项目（第 4 周选题时可参考）**

| 方向 | 项目 |
|---|---|
| 综合最全 | <https://github.com/Lvyizhuo/MyProject-SDAgent> |
| 工程完整 | <https://github.com/lishuangqiang/AI-Meeting> |
| 单测齐全 | <https://github.com/keweizhuazuki/kewei-ai-agent> |
| RAG 完整 | <https://github.com/t1804330987/DD_Rag> |
| RAG 最小 | <https://github.com/nageoffer/tinyrag> |
| 手搓框架 | <https://github.com/ZhuofanWu/TaxiAgent> |

> 打开任何一个之前先扫两样：① 最后提交时间 ② star。**半年没动的别学。**

---

## 第 1 周 · 跑通 + 摸骨架

### Day 1 — 环境就绪
- [ ] JDK / Maven 版本确认（要 Java 17+）
- [ ] 配好一个模型 key（DashScope 或 DeepSeek）
- [ ] **产出**：`config.properties` 能连通模型

### Day 2 — ⭐ 两个 Hello World，跑通为止
- [ ] `agentscope-java` 的最小例子跑起来
- [ ] `spring-ai-alibaba` 的 helloworld 跑起来
- [ ] **产出**：两个能对话的本地程序

### Day 3 — 翻目录，不读代码
- [ ] 两个仓库各翻一遍目录树
- [ ] 回答三个问题：入口类在哪？agent 怎么定义？工具怎么注册？
- [ ] **产出**：一张自己画的架构图（丑没关系）

### Day 4-5 — 跑示例
- [ ] `agentscope-examples/` 挑 2 个跑通
- [ ] `spring-ai-alibaba/examples` 挑 2 个跑通
- [ ] **产出**：每个示例写 3 行「它做了什么」

### Day 6-7 — ⭐ 不抄教程，手搓一个 agent
- [ ] 用 ToyAgent 里学的知识，纯手写一个带工具调用的 agent（30 行以内）
- [ ] 工具：一个查天气 + 一个算数
- [ ] **产出**：能跑的 `MyFirstAgent.java`

---

## 第 2 周 · 官方重点 + 补 AI 概念

### Day 8-10 — ⭐ 精读 `examples/deepresearch`
- [ ] 对着 ToyAgent 对照表读：
  - 子代理协作 ↔ Step13 `SubagentAgent`
  - 上下文管理 ↔ Step17 `RuntimeAgent`
  - MCP 工具 ↔ Step09 `McpAgent`
  - 任务规划 ↔ Step12 / Step16
- [ ] **产出**：一份笔记 —— 它的每个模块对应 ToyAgent 的哪个 step

### Day 11-12 — RAG 最小版
- [ ] 跑 `tinyrag`，或者自己写一个查本地文档的问答
- [ ] **产出**：能查你自己准备的 10 个文档

### Day 13-14 — 换成真向量检索
- [ ] 上 pgvector（或任意向量库）
- [ ] 把 Day 11-12 的换成真检索
- [ ] **产出**：带向量库的 RAG

---

## 第 3 周 · 工程化 ⭐（你的差异化在这）

> 前两周谁都能过。**这一周的四件事，是「能跑」和「能上生产」的分水岭。**

### Day 15-16 — 上下文管理
- [ ] 给 Day 6 的 agent 加上下文预算 + 裁剪
- [ ] **产出**：故意聊 30 轮，看它触发裁剪
- [ ] 参考：ToyAgent Step17 `trimToBudget()`

### Day 17-18 — ⭐ 人机协同（挂起 / 恢复）
- [ ] 状态**存进数据库**，不是内存
- [ ] **产出**：挂起后重启进程，再答还能续上
- [ ] 参考：ToyAgent Step19 `AskAgent` —— **注意它那个「重启不丢」的注释是假的，状态其实在内存里，你来写真的**

### Day 19-20 — 工具权限
- [ ] 给危险工具加「需要确认」
- [ ] **产出**：调危险工具时能拦住
- [ ] 参考：ToyAgent Step20 `ApprovalAgent`

### Day 21 — ⭐ 评测意识
- [ ] 准备 10 个问题 + 期望答案
- [ ] 写脚本自动跑、算通过率
- [ ] **产出**：一个能告诉你「改完变好还是变坏」的东西

---

## 第 4 周 · 做成能讲的项目 + 投

### Day 22-24 — 定题
- [ ] 选一个**真实的小问题**（越具体越好，**别做「万能助手」**）
- [ ] **产出**：一页纸的需求 + 设计

### Day 25-28 — 实现 + 部署
- [ ] 把它做出来，**部署到公网**
- [ ] **产出**：一个能打开就用的 URL

### Day 29 — 包装
- [ ] 写 README（说清：解决什么问题、怎么做的、卡在哪）
- [ ] 录一个 3 分钟演示
- [ ] **产出**：能直接发给别人的东西

### Day 30 — 投
- [ ] 改简历（把 Day 29 那个东西顶到最前面）
- [ ] **投第一批 10 家**
- [ ] **产出**：10 份投递

---

## 「学会了」的验收清单

勾完这五条，就可以投了：

- [ ] 能**从零**搭一个带工具调用的 agent（不抄教程）
- [ ] 上下文超限时知道怎么办
- [ ] 会做人机协同（挂起 → 恢复）
- [ ] **有评测意识** —— 怎么知道它变好了？
- [ ] 一个**部署在线的**项目（哪怕只有 10 个人用）

---

## 每天的固定动作（15 分钟）

- [ ] 下班前写 3 行：**今天做了什么 / 卡在哪 / 明天第一步**

> 一个月下来，这 90 行就是你面试时的素材库。

---

## 进度记录

| 周 | 完成度 | 卡住的点 | 下周调整 |
|---|---|---|---|
| 第 1 周 | ☐ ☐ ☐ ☐ ☐ ☐ ☐ | | |
| 第 2 周 | ☐ ☐ ☐ ☐ ☐ ☐ ☐ | | |
| 第 3 周 | ☐ ☐ ☐ ☐ ☐ ☐ ☐ | | |
| 第 4 周 | ☐ ☐ ☐ ☐ ☐ ☐ ☐ ☐ ☐ | | |

---

## 三句要记住的话

1. **框架一两个月就能「会用」。** 从「会用」到「能讲清楚为什么」，再到「有一个跑在线上的东西」—— 那才是六个月。
2. **市场不缺会调 API 的人。** 缺的是**知道模型什么时候不靠谱、怎么兜**的人 —— 这正是第 3 周在练的。
3. **最值钱的不是学框架，是攒那几个能讲的故事。**
