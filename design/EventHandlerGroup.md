Disruptor 源码解析：EventHandlerGroup

在 Disruptor 中，EventHandlerGroup 是一个用于组织消费者、建立消费者依赖关系，以及编排消费拓扑的核心类。

前面我们分析了：

* SingleProducerSequencer：单生产者的序列分配与发布机制。
* MultiProducerSequencer：多生产者并发申请序列及发布机制。
* ProcessingSequenceBarrier：协调事件可用性和消费者依赖关系。
* BlockingWaitStrategy：消费者等待事件的策略。
* BatchEventProcessor：驱动消费者获取并处理事件。

接下来分析 EventHandlerGroup，可以把视角从单个组件转向整个 Disruptor 的消费者依赖关系和 DAG 执行模型。

本文以 Disruptor 3.x 的典型 API 和实现为主。不同版本在泛型、方法签名和内部实现上可能有所差异。

⸻

一、EventHandlerGroup 的作用

1.1 为什么需要 EventHandlerGroup？

假设我们正在开发一个交易所系统。

一笔成交事件产生后，需要执行三个操作：

1. 更新订单状态。
2. 更新账户资产。
3. 更新行情数据。

这三个操作之间没有依赖关系，可以并行处理。

                TradeEvent
                     |
           +---------+---------+
           |         |         |
           v         v         v
        订单处理    资产处理    行情处理

如果使用 Disruptor，可以通过多个 EventHandler 处理同一批事件。

但是，如果后续还需要实现以下逻辑：

* 订单状态和账户资产都更新完成后，才发送成交通知。
* 成交通知发送完成后，才执行后续统计。

消费拓扑就变成了：

                   TradeEvent
                        |
              +---------+---------+
              |         |         |
              v         v         v
           订单处理    资产处理    行情处理
              |         |
              +----+----+
                   |
                   v
                成交通知
                   |
                   v
                 统计

这里就涉及两个重要问题：

* 如何组织多个消费者？
* 如何声明某个消费者必须等待哪些上游消费者完成？

EventHandlerGroup 就是 Disruptor 用来组织这类消费关系的 API 抽象之一。

它本身不负责消费事件，而是负责帮助构建消费者之间的依赖关系。

⸻

二、EventHandlerGroup 的整体定位

先看 Disruptor 的几个核心组件之间的关系。

                       Disruptor
                           |
                           v
                    EventHandlerGroup
                           |
                 组织 EventHandler
                           |
                           v
                 EventProcessor / Sequence
                           |
                           v
                   SequenceBarrier
                           |
                           v
                      RingBuffer

这里需要纠正一个容易产生的误解：EventHandlerGroup 并不是 BatchEventProcessor 的运行时父类，也不负责驱动处理器线程。

它主要存在于消费者拓扑的构建阶段。

例如：
```
EventHandlerGroup<TradeEvent> group =
disruptor.handleEventsWith(
orderHandler,
assetHandler,
marketHandler
);
```
这段代码注册三个消费者，并返回一个 EventHandlerGroup，供后续继续声明依赖关系。

接下来可以这样写：
```
group.then(notificationHandler);
```
其含义是：

notificationHandler 依赖前面这个组中的消费者完成相应事件的处理进度。

因此，可以把 EventHandlerGroup 理解为一个消费者依赖关系的组合与编排对象。

⸻

三、核心源码结构

在 Disruptor 3.x 的典型实现中，EventHandlerGroup<T> 的结构大致如下：
```
public class EventHandlerGroup<T> {
private final Disruptor<T> disruptor;
private final ConsumerRepository<T> consumerRepository;
private final Sequence[] sequences;
// ...
}
```
具体字段和方法可能随版本变化，但这三个成员体现了它的设计思想。

3.1 disruptor
```
private final Disruptor<T> disruptor;
```
保存所属的 Disruptor 实例。

它使 EventHandlerGroup 能够继续通过 Disruptor 的 API 创建新的消费者、建立依赖关系。

例如：
```
group.then(nextHandler);
```
内部需要借助 Disruptor 将新的处理器注册到整个消费拓扑中。

3.2 consumerRepository
```
private final ConsumerRepository<T> consumerRepository;
```
ConsumerRepository 用于管理已经注册的消费者及其相关信息。

在典型实现中，它负责维护处理器、序列以及相关消费组件之间的对应关系。

例如：
```
ConsumerRepository
|
+-- Handler A -> Processor A -> Sequence A
|
+-- Handler B -> Processor B -> Sequence B
|
+-- Handler C -> Processor C -> Sequence C
```
这样，Disruptor 就可以根据注册信息查找处理器、构建依赖关系，并管理消费者的生命周期。

3.3 sequences

private final Sequence[] sequences;

这是 EventHandlerGroup 中最值得关注的成员。

它保存当前组所代表的消费者进度序列。

例如：
```
EventHandlerGroup
|
+-- Sequence A
|
+-- Sequence B
|
+-- Sequence C
```
假设：
```
Sequence A = 100
Sequence B = 95
Sequence C = 98
```
如果下游消费者必须等待 A、B、C 全部处理到目标序列，那么其可处理进度就受到这三个序列的共同约束。

概念上：

\text{可处理上限} = \min(A,B,C)

上述例子中：

\min(100,95,98)=95

这里的最小值是帮助理解依赖关系的简化模型。实际的可用序列还涉及生产者发布状态、连续可用性及 SequenceBarrier 的具体实现。

核心思想是：当一个消费者依赖多个上游消费者时，不能越过其中任何一个尚未完成的依赖进度。

⸻

四、EventHandlerGroup 的关键方法

下面介绍几个最重要的方法。
```
方法	作用
then(...)	在当前组之后注册新的消费者
thenHandleEventsWith(...)	声明后续消费者
and(...)	将其他消费者或消费者组纳入当前组
asSequenceBarrier()	将当前组对应的序列转换为 SequenceBarrier
handleEventsWith(...)	通常从 Disruptor 入口开始注册消费者
```
其中，部分方法的名称、参数及重载会因 Disruptor 版本而异。

另外，handleEventsWith(...) 通常是 Disruptor 自身提供的入口方法，而不是 EventHandlerGroup 独有的方法。

下面重点分析消费者依赖关系。

⸻

五、then()：建立消费者依赖关系

这是理解 EventHandlerGroup 最重要的方法。

5.1 基本用法
```
EventHandlerGroup<TradeEvent> group =
disruptor.handleEventsWith(
orderHandler,
assetHandler
);
group.then(notificationHandler);
```
其消费拓扑是：

                  RingBuffer
                      |
              +-------+-------+
              |               |
              v               v
          OrderHandler   AssetHandler
              |               |
              +-------+-------+
                      |
                      v
             NotificationHandler

这里有两个关键点。

第一，OrderHandler 和 AssetHandler 可以并行处理。

它们都是直接依赖生产者发布的事件，没有声明彼此之间的先后关系。

第二，NotificationHandler 依赖两者。

它需要等待上游组中的两个消费者都推进到相应序列，才能继续处理该序列对应的事件。

这并不意味着两个上游消费者必须同时运行，也不意味着它们必须使用同一个线程。

⸻

5.2 then() 的实现思路

在典型的 Disruptor 3.x 实现中，then(...) 的核心逻辑可以概括为：
```
public EventHandlerGroup<T> then(
EventHandler<? super T>... handlers) {
return disruptor.createEventProcessors(
sequences,
handlers
);
}
```
这是简化示意，不是所有版本的逐字源码。

重点在于：

sequences

这些序列被传给 Disruptor，作为新消费者的依赖序列。

创建新的消费者时，Disruptor 会根据这些依赖序列构建相应的 SequenceBarrier，再将处理器注册到消费者管理结构中。

可以把调用链理解为：
```
group.then(notificationHandler)
|
v
disruptor.createEventProcessors(...)
|
v
使用上游 sequences
|
v
创建 SequenceBarrier
|
v
创建消费者处理器
|
v
注册新的消费节点
```
因此，then() 并不是简单地把一个处理器追加到列表末尾，而是把上游消费者的进度关系传递给下游消费者。

⸻

六、and()：合并消费者组

and() 的作用是把其他消费者或消费者组纳入当前组。

例如：
```
EventHandlerGroup<TradeEvent> groupA =
disruptor.handleEventsWith(orderHandler);
EventHandlerGroup<TradeEvent> groupB =
disruptor.handleEventsWith(assetHandler);
EventHandlerGroup<TradeEvent> combined =
groupA.and(groupB);
combined.then(notificationHandler);
```
概念上的依赖关系：

              RingBuffer
                   |
             +-----+-----+
             |           |
             v           v
        OrderHandler  AssetHandler
             |           |
             +-----+-----+
                   |
                   v
          NotificationHandler

notificationHandler 需要等待两个组所代表的消费者进度。

6.1 and() 与 then() 的区别

这是面试中很容易被问到的一个问题。

then()

表达的是先完成当前组，再执行后续消费者。

groupA.then(handlerC);

概念上：

A -> C

如果组 A 包含多个消费者：

      A1
     /  \
    A2  A3

那么后续消费者必须等待组 A 所代表的全部相关依赖。

and()

表达的是将多个消费者或消费者组组合成一个依赖集合。

groupA.and(groupB);

概念上：

A + B

它本身不表示新增的执行阶段，也不表示 A 与 B 之间存在先后关系。

只有在这个组合上继续调用 then()，才会声明下游依赖：

groupA.and(groupB).then(handlerC);

也就是：

A ──┐
├──> C
B ──┘

一句话总结：and() 用于组合依赖，then() 用于在依赖之后声明新的消费阶段。

⸻

七、asSequenceBarrier()：把组转换成 SequenceBarrier

这个方法连接了消费者拓扑构建与事件消费执行。

典型调用：
```
SequenceBarrier barrier =
group.asSequenceBarrier();
```
其核心思想是使用当前组中的序列创建一个 SequenceBarrier。

概念上类似：
```
public SequenceBarrier asSequenceBarrier() {
return disruptor.getRingBuffer()
.newBarrier(sequences);
}
```

这同样是简化示意，具体实现取决于版本。

7.1 为什么需要 SequenceBarrier？

假设：
```
Consumer A: sequence = 100
Consumer B: sequence = 95
```
下游消费者依赖 A 和 B。

它不能仅仅因为 A 已经处理到 100，就开始处理 100。

因为 B 还没有处理到 100。

通过基于 A、B 的序列创建 SequenceBarrier，下游消费者就能等待相关依赖满足。

整个过程可以理解为：
```
EventHandlerGroup
|
v
Sequence[]
|
v
SequenceBarrier
|
v
waitFor()
|
v
检查依赖进度
|
v
返回可处理的连续序列范围
```
这里要特别区分：

* EventHandlerGroup：在构建阶段组织依赖。
* SequenceBarrier：在运行阶段协调事件可用性。
* BatchEventProcessor：在运行阶段实际处理事件。

三者分工不同。

⸻

八、通过实际例子理解 EventHandlerGroup

我们使用一个交易所的成交处理场景。

假设一笔成交事件需要经过以下处理：

1. 更新订单状态。
2. 更新账户资产。
3. 更新行情数据。
4. 订单和资产更新完成后，发送成交通知。
5. 通知完成后，执行统计。

8.1 定义事件处理器

首先定义四个处理器：
```
EventHandler<TradeEvent> orderHandler =
(event, sequence, endOfBatch) -> {
updateOrder(event);
};
EventHandler<TradeEvent> assetHandler =
(event, sequence, endOfBatch) -> {
updateAsset(event);
};
EventHandler<TradeEvent> marketHandler =
(event, sequence, endOfBatch) -> {
updateMarket(event);
};
EventHandler<TradeEvent> notificationHandler =
(event, sequence, endOfBatch) -> {
sendNotification(event);
};
```
这里使用 Lambda 表达式简化代码。

8.2 注册第一阶段消费者
```
EventHandlerGroup<TradeEvent> group =
disruptor.handleEventsWith(
orderHandler,
assetHandler,
marketHandler
);
```
此时：

                 TradeEvent
                      |
           +----------+----------+
           |          |          |
           v          v          v
         Order      Asset      Market

三个处理器可以独立消费同一批事件。

8.3 注册第二阶段消费者

group.then(notificationHandler);

拓扑变成：

                 TradeEvent
                      |
           +----------+----------+
           |          |          |
           v          v          v
         Order      Asset      Market
           |          |          |
           +-----+----+          |
                 |               |
                 v               |
           Notification          |

但是，这张图容易产生一个误解。

按照上述代码，notificationHandler 依赖 group 中的全部三个消费者，包括 marketHandler。

如果业务只要求订单和资产更新完成后就发送通知，而不必等待行情处理，那么应该单独组织依赖组。

例如：
```
EventHandlerGroup<TradeEvent> orderAndAsset =
disruptor.handleEventsWith(
orderHandler,
assetHandler
);
disruptor.handleEventsWith(marketHandler);
orderAndAsset.then(notificationHandler);
```
这样得到的拓扑才是：

                 TradeEvent
                      |
           +----------+----------+
           |          |          |
           v          v          v
         Order      Asset      Market
           |          |
           +-----+----+
                 |
                 v
           Notification

这里 notificationHandler 只依赖订单和资产处理器，不依赖行情处理器。

这个例子说明：消费者组的边界非常重要。依赖组包含哪些消费者，就决定了下游处理器需要等待哪些消费者。

⸻

九、EventHandlerGroup 与消费者依赖 DAG

Disruptor 的消费者依赖关系可以建模为有向无环图，也就是 DAG（Directed Acyclic Graph）。

例如：

                    Producer
                        |
            +-----------+-----------+
            |           |           |
            v           v           v
            A           B           C
            |           |           |
            +-----+-----+           |
                  |                 |
                  v                 |
                  D                 |
                  |                 |
                  +--------+--------+
                           |
                           v
                           E

对应关系：

* A、B、C 直接消费生产者发布的事件。
* D 依赖 A 和 B。
* E 依赖 D 和 C。

在代码中，可以通过消费者组组合和 then() 构建相应关系。

例如：
```
EventHandlerGroup<TradeEvent> groupAB =
disruptor.handleEventsWith(handlerA, handlerB);
EventHandlerGroup<TradeEvent> groupC =
disruptor.handleEventsWith(handlerC);
EventHandlerGroup<TradeEvent> groupD =
groupAB.then(handlerD);
groupD.and(groupC).then(handlerE);
```
概念上的依赖关系：
```
A ──┐
├──> D ──┐
B ──┘        ├──> E
C ───────────┘
```
这里需要注意，示例侧重于说明依赖关系的表达方式。具体 API 的泛型、方法签名和拓扑注册细节应以项目实际使用的 Disruptor 版本为准。

DAG 设计带来的优势是：

1. 可以明确消费者之间的先后约束。
2. 没有依赖关系的消费者可以并行执行。
3. 可以根据业务需要构建多阶段消费流程。
4. 可以避免不必要的全局串行化。

⸻

十、EventHandlerGroup 与 BatchEventProcessor 的区别

这是另一个需要明确区分的地方。
```
比较维度	EventHandlerGroup	BatchEventProcessor
核心职责	组织消费者依赖关系	执行事件消费
所处阶段	主要用于拓扑构建	运行时消费
是否直接调用业务处理器	否	是
是否维护消费进度	保存所代表的 Sequence 引用	维护自身的 Sequence
是否负责等待事件	不直接负责	通过 SequenceBarrier 等待
是否负责创建消费线程	否	实现 Runnable，可由线程执行
是否负责构建依赖	是	使用已经建立的依赖关系
```
可以用一句话记忆：

EventHandlerGroup 决定谁依赖谁，BatchEventProcessor 负责真正执行消费。

而 SequenceBarrier 负责在运行过程中协调这些依赖关系。

⸻

十一、EventHandlerGroup 为什么重要？

从架构设计角度看，EventHandlerGroup 的价值不仅是让 API 更简洁。

它还帮助我们表达业务上的并行和依赖关系。

例如，在交易所系统中：

                 TradeEvent
                      |
           +----------+----------+
           |          |          |
           v          v          v
         订单服务    资产服务    行情服务
           |          |
           +-----+----+
                 |
                 v
              成交通知

这里有三类重要关系。

1. 并行关系

订单、资产、行情处理互相独立时，可以并行执行。

2. 汇聚关系

成交通知需要等待订单和资产处理完成。

3. 阶段关系

通知完成后，还可以继续执行后续统计或归档。

合理的消费者拓扑能够减少不必要的等待，同时保持业务所要求的顺序约束。

不过，Disruptor 的消费者依赖只保证相应事件处理进度上的先后约束，不等同于跨数据库、跨服务的分布式事务保证。

如果订单已经更新而资产更新失败，消费者依赖关系本身并不能自动回滚订单更新。

这类问题仍然需要通过业务幂等、事务设计、补偿和对账等机制解决。

⸻

十二、面试总结

建议重点记住以下几个问题。

Q1：EventHandlerGroup 的作用是什么？

参考回答：

EventHandlerGroup 用于组织 Disruptor 的消费者，并通过消费者对应的 Sequence 建立依赖关系。

它本身不负责执行事件消费，而是帮助构建消费拓扑，使得没有依赖关系的消费者可以并行执行，而下游消费者可以等待指定的上游消费者完成。

Q2：then() 和 and() 有什么区别？

参考回答：

then() 用于在当前消费者组之后注册新的消费者，建立上下游依赖关系。

and() 用于将其他消费者或消费者组纳入当前组，组合它们的依赖序列。

例如：

groupA.and(groupB).then(handlerC);

表示 handlerC 依赖 A、B 两组对应的消费进度。

Q3：EventHandlerGroup 如何实现消费者依赖？

参考回答：

EventHandlerGroup 保存当前组对应的 Sequence。注册下游消费者时，这些序列会被用于创建相应的 SequenceBarrier。

下游消费者通过 SequenceBarrier 等待上游消费者推进到相应的序列，从而实现依赖关系。

Q4：EventHandlerGroup 和 SequenceBarrier 有什么区别？

参考回答：

EventHandlerGroup 主要负责构建阶段的消费者组织与依赖声明。

SequenceBarrier 负责运行阶段的等待和可用性协调。

前者描述依赖关系，后者在消费过程中执行相关协调。

Q5：为什么 Disruptor 使用 DAG？

参考回答：

DAG 可以明确表达事件处理之间的先后关系，同时允许没有依赖关系的消费者并行执行。

这样既能维持必要的处理顺序，也能避免把所有业务处理都放在一个串行消费者中，提升多阶段事件处理流程的并行度。

⸻

最后：把核心调用链串起来

结合前面学习的源码，可以将 Disruptor 的消费者构建与运行过程总结为：
```
Disruptor.handleEventsWith(...)
|
v
EventHandlerGroup
|
v
then() / and()
|
v
ConsumerRepository
|
v
SequenceBarrier
|
v
BatchEventProcessor
|
v
EventHandler
```
需要注意，这是一张概念性的关系图，并非严格的一对一调用栈。

其中最值得深入理解的是三个问题：

1. EventHandlerGroup 如何通过 Sequence[] 表达消费者依赖？
2. SequenceBarrier 如何根据上游消费者的进度确定可处理的序列范围？
3. BatchEventProcessor 更新消费进度后，如何影响下游消费者以及生产者的容量控制？

把这三个问题连起来，你就能真正理解 Disruptor 的消费者依赖模型，而不只是记住几个 API 的用法。