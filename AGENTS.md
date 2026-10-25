# AGENTS.md — 给自动化代理的入口

> 本文件面向**所有** AI 编码代理（Claude Code / Copilot / Cursor / Codex / 自研 agent 等）。
> 目标：让你在**不猜**的前提下驱动 BlackBoxPro 做客户端侧黑盒测试。

---

## 0. 30 秒判断：这是不是你要的工具？

**是**，如果你要验证的是**真实客户端能看到/能做到什么**：
- GUI / 容器 / 槽位内容 / tooltip / 聊天样式 / 记分板 / bossbar / tablist 是否出现、内容对不对
- 渲染结果（截图）是否正常
- 输入模拟：移动、点击、挖掘、放置、交互、按键、命令补全
- 客户端侧状态查询（NBT、玩家状态、路径移动）

**不是**，如果你要验证的是：
- 服务端插件业务逻辑（经济/权限/数据库）—— 用单元测试或 RCON
- 服务端日志内容 —— 用 RCON
- 多个客户端并发交互 —— 需要 bot（如 headlessmc）
- 真实玩家协议层行为 —— 需要专门协议库

---

## 1. ⚠️ 最重要的一条：不要误读 `run_test` 的结果

```
run_test 的 passed/failed  描述的是  框架自身 action 在当前环境是否可用
                           NOT        你的插件是否通过了测试
```

三条机制性理由：

1. **触发路径不同**：你插件的命令/界面不会被 `run_test` 自动触达。
2. **断言对象不同**：`run_test` 的用例表在 `BlackBoxTestCatalog` 里**硬编码为 ActionCatalog 自测**，不是可扩展的插件测试套件。
3. **语义错位**：它测的是"客户端能不能做到某个动作"，不是"你的业务逻辑对不对"。

**把它当作"我的插件通过了测试"，是假阳性。**

### 正确做法：自己编排 action 序列

```
# 例：验证 /myplugin gui 打开的界面是否正确
POST /execute {"action":"chat_command","params":{"command":"/myplugin gui"}}
POST /execute {"action":"query_screen_state"}
POST /execute {"action":"query_container_slots"}
POST /execute {"action":"screenshot","params":{"name":"myplugin-gui"}}
POST /execute {"action":"close_container"}
```

`run_test` 只当**链路体检**：确认框架与你的环境握手正常。

---

## 2. 最小上手路径

### 2.1 拓扑

```
你的 Agent ──HTTP 38080──> Paper 测试服务端（BlackBoxPro 插件 + 你的被测插件）
                                   ▲
                                   │ 游戏协议
                                   ▼
                           Fabric 客户端（BlackBoxPro Mod，HTTP 38081，硬编码）
```

### 2.2 四条硬约束（违反会得到**误导性结果**）

| 约束 | 违反后果 |
|---|---|
| **窗口不可最小化** | "移动光标"类 action 会在数毫秒内失败，且失败文案不可读（`Cannot coerce value to an empty range: maximum -1.0 is less than minimum 0.0`） |
| **`pauseOnLostFocus: false`** | 默认 `true`，失焦会弹暂停菜单 ⇒ 玩家物理冻结 ⇒ `jump`/`click_slot`/`close_container` 连锁失败 |
| **同一时刻只能一个客户端** | 38081 硬编码且 `RuntimeBlackBoxConfig.update()` 在 `dev-2.0` 零调用方 ⇒ 两个客户端互相顶掉 |
| **收口 HTTP 到回环** | 7 处 `HttpServer.create(InetSocketAddress(port),0)` **默认绑 `0.0.0.0`**、**零鉴权** ⇒ 对局域网敞开 |

### 2.3 调用约定

- 端点：`POST /status`、`POST /execute`。**`/actions` 不存在**（常量未注册，访问得 404）。
- `run_test` **必传 `params.player`**（在线玩家名，先用客户端 `query_tab_list` 取）；`scope` 只有 `smoke` / `full`。
- **结果 JSON 必须用 node 解析**：PowerShell 5.1 的 `Get-Content` 默认 GBK、`ConvertFrom-Json` 行为与 node 不同。
- **写 JSON 不要用 `>` 重定向**（PS 5.1 会写 UTF-16LE）：
  ```powershell
  [System.IO.File]::WriteAllText($p, $s, (New-Object System.Text.UTF8Encoding($false)))
  ```
- **请求串行化**：HTTP server 单线程处理，并发会排队甚至超时。
- **`actions` 字段 = 运行时注册表大小**（唯一不写死的计数来源）；文档里的数字**都不要信**。

---

## 3. 环境未就绪 ≠ 被测程序失败

把下列情况归为**环境问题**，不要记成缺陷：

| 现象 | 归属 | 处置 |
|---|---|---|
| 端口连不上 | 环境 | 等就绪 / 检查进程 |
| `ready: false` | 环境 | 客户端还在加载 |
| `Player not online` | 环境 | 先 `join_world` / `connect_to_server` |
| `Missing param: player` | 调用错误 | 补 `params.player` |
| 窗口最小化相关报错 | 环境 | 恢复窗口 |
| `create_world` 超时（10s） | 环境 | 改用 `join_world {worldName}` 复用已有世界 |
| 首次启动卡在下载 assets | 环境 | 等，或预置 `run/downloads` |

---

## 4. 安全（不可协商）

这是**本机开发工具**，不是生产服务：

- HTTP **明文 + 零鉴权**；`chat_command` 能发**任意服务端命令**（等级继承该账号）、`stop_server` 一行停服、`query_chat_history` 读**全部聊天**、`screenshot` 把画面**落盘**。
- **只 bind 回环**，并**从另一台机器验证确实超时**（只验本机通不算）。
- **只在隔离测试服上用**；不要接主力世界/生产服。
- 不要暴露到公网（端口转发 / frp / 云安全组）。

---

## 5. 详细文档在哪

| 需要什么 | 读这个 |
|---|---|
| 完整操作流程（技能主文档） | `.claude/skills/blackboxpro-test/SKILL.md` |
| 全部 action 与参数 | `.claude/skills/blackboxpro-test/reference/action-catalog.md` |
| HTTP 端点、字段、错误语义 | `.claude/skills/blackboxpro-test/reference/http-api.md` |
| 新增一个 action | `.claude/skills/blackboxpro-test/reference/register-action.md` |
| 接入新 MC 版本 | `.claude/skills/add-mc-version/skill.md` |

**不要预加载全部文档**：先读本文件，再按需读取上面某一份。

---

## 6. 本仓库的约定

- 默认分支 `dev-2.0`；`mod/1.21.4` **只存在于 `feat/1.21.4-fabric` 分支**（PR #3）。
- 文档漂移由 CI 拦：`.github/workflows/validate-skills.yml` 跑 `.claude/skills/validate_blackboxpro_skills.py`。
  - 它**现场解析源码**得出 action 计数，因此**文档里不要写死任何 action 数字** —— 写了就会红。
  - 本地手动跑：`python .claude/skills/validate_blackboxpro_skills.py`（退出码 0 = 通过）。
