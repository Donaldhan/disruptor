# Disruptor 工程架构与核心功能点总结

## 1. 项目定位

`disruptor` 是一个高性能的 **线程间消息传递库**，核心目标是提供低延迟、高吞吐的事件处理能力。  
与传统 `BlockingQueue` 相比，它强调以下特性：

- 多消费者广播（Multicast）而非单消费者独占消费
- 通过依赖关系构建消费者处理拓扑（gating/dependency graph）
- 事件对象预分配，降低运行期 GC 压力
- 可选无锁并发路径（除部分等待策略外）

---

## 2. 仓库结构（按职责）

- `src/main/java/com/lmax/disruptor`
  - 核心并发模型与数据结构：`RingBuffer`、`Sequencer`、`SequenceBarrier`、`BatchEventProcessor`、`WaitStrategy` 等
- `src/main/java/com/lmax/disruptor/dsl`
  - 面向业务使用者的装配 DSL：`Disruptor`、`EventHandlerGroup`、`ProducerType` 等
- `src/examples/java`
  - 使用示例（lambda、translator、legacy API、多种消费拓扑）
- `src/test/java`
  - 功能与并发语义测试
- `src/perftest/java`
  - 吞吐/延迟性能基准与对比实验
- `src/jmh/java`
  - JMH 微基准（局部热点性能）
- `src/jcstress/java`
  - 并发可见性与竞态正确性压力测试
- `src/docs/asciidoc`
  - 用户指南、实现说明、开发者文档（发布、测试与优化）

---

## 3. 核心架构分层

### 3.1 数据与序列层（Data + Sequence）

- **`RingBuffer<E>`**
  - 固定大小循环数组，大小必须是 2 的幂
  - 初始化时通过 `EventFactory` 预分配事件对象
  - 提供声明序号、填充事件、发布事件、批量发布等能力
- **`Sequence`**
  - 用序号追踪生产/消费进度
  - 在并发控制中承担“全局进度坐标”角色，并做了伪共享规避

### 3.2 协调与并发控制层（Coordination）

- **`Sequencer`（接口）**
  - 负责序号分配、可用性判断、gating sequence 管理、构建 `SequenceBarrier`
  - 是核心并发算法的抽象中心
- **`SingleProducerSequencer` / `MultiProducerSequencer`**
  - 分别优化单生产者与多生产者写入路径
  - 控制是否可申请序号、是否覆盖慢消费者尚未处理的数据
- **`SequenceBarrier` / `ProcessingSequenceBarrier`**
  - 消费端等待屏障：判断“序号是否可安全消费”
  - 结合上游依赖与游标推进实现消费者拓扑约束

### 3.3 消费执行层（Processing）

- **`BatchEventProcessor`**
  - 默认事件循环执行器
  - 批量拉取可用序号范围并回调 `EventHandler`
  - 处理生命周期（start/shutdown）、超时、异常及可重绕（rewind）语义
- **`EventHandler` / `RewindableEventHandler`**
  - 用户扩展点：业务处理逻辑入口

### 3.4 装配与易用层（DSL）

- **`dsl.Disruptor<T>`**
  - 负责构建 `RingBuffer + Processor + Barrier + 依赖关系`
  - 支持 `handleEventsWith(...).then(...)` 链式编排
  - 统一生命周期入口：`start()` / `shutdown()` / `halt()`
- **`EventHandlerGroup` / `ConsumerRepository`**
  - 表达与维护消费者依赖图，推进 gating 更新

### 3.5 等待策略层（Latency/CPU Tradeoff）

- `BlockingWaitStrategy`：延迟更高但 CPU 开销低，通用默认策略
- `SleepingWaitStrategy`：在延迟与 CPU 之间折中
- `YieldingWaitStrategy` / `BusySpinWaitStrategy`：以更高 CPU 占用换更低延迟
- `PhasedBackoffWaitStrategy`：分阶段退避，适配复杂场景

---

## 4. 典型事件流转

1. 生产者通过 `RingBuffer`/`Disruptor` 发布事件（单条或批量）
2. `Sequencer` 分配可写序号并在发布后标记可见
3. `SequenceBarrier` 判断消费者是否可读取目标序号
4. `BatchEventProcessor` 按可用区间批处理并回调 `EventHandler`
5. 消费者更新自身 `Sequence`，作为下游依赖与生产者 gating 的依据
6. `Sequencer` 依据最慢叶子消费者序号防止 RingBuffer 回绕覆盖

---

## 5. 核心功能点

- **高吞吐低延迟事件总线**：通过序号驱动和批处理循环减少同步与上下文切换开销
- **多播消费模型**：一个事件可被多个消费者按相同顺序并行处理
- **消费者依赖图**：支持 `A -> (B,C) -> D` 这类流水线/汇聚拓扑
- **预分配内存模型**：降低对象创建与 GC 抖动
- **生产者模型可配置**：`ProducerType.SINGLE/MULTI` 适配不同并发写入模式
- **等待策略可调**：根据部署环境选择延迟优先或资源优先
- **批量发布与批量消费**：降低每条消息固定开销
- **异常与重试控制**：统一 `ExceptionHandler`，支持可重绕批处理策略

---

## 6. 工程质量与验证体系

- **单元/集成测试**：`src/test/java` 覆盖行为正确性
- **性能测试**：`src/perftest/java` + `src/jmh/java` 覆盖吞吐/延迟与热点性能
- **并发语义验证**：`src/jcstress/java` 检查内存可见性与竞态正确性
- **文档体系**：`src/docs/asciidoc` 提供使用、设计与开发流程说明
- **CI 工作流**：`.github/workflows` 提供构建、CodeQL、文档与包装校验

---

## 7. 结论

该工程采用“**环形缓冲 + 序号协调 + 屏障依赖 + 批处理事件循环**”的分层架构，将并发控制与业务处理解耦。  
通过可配置的生产者模型、等待策略和消费者依赖图，Disruptor 可以在不同硬件与负载条件下平衡延迟、吞吐与 CPU 成本，特别适用于撮合、风控、日志、复制、异步流水线等对性能敏感的场景。

