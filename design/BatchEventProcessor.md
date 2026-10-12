Disruptor 源码解析：BatchEventProcessor

在 Disruptor 中，BatchEventProcessor 是事件消费的核心执行器，负责从 RingBuffer 中获取已经发布的事件，调用业务处理器进行消费，并推进消费进度。

结合你前面分析的 SingleProducerSequencer、MultiProducerSequencer、ProcessingSequenceBarrier 和 BlockingWaitStrategy，我们可以把 BatchEventProcessor 放到整个消费链路中理解。

重点分析以下几个方面：

1. BatchEventProcessor 在 Disruptor 中的定位。
2. 核心成员变量及其作用。
3. run() 方法的完整执行流程。
4. processEvents() 如何批量消费事件。
5. SequenceBarrier 如何控制事件的可见性和消费顺序。
6. Sequence 如何推进，以及为什么必须正确更新。
7. 异常处理、批量消费和消费终止机制。
8. 面试中常见的相关问题。

⸻

一、BatchEventProcessor 的定位

1.1 整体架构

先看 Disruptor 的基本生产消费模型。

                  Producer
                     |
                     v
              RingBuffer
                     |
                     v
                 Sequencer
                     |
           发布事件并推进 Cursor
                     |
                     v
           SequenceBarrier
                     |
                     v
           BatchEventProcessor
                     |
             EventHandler
                     |
                     v
                业务逻辑

各个组件的职责如下：
```
组件	核心职责
RingBuffer	存储事件，提供事件槽位
Sequencer	管理生产者的序列分配、发布及可用性
SequenceBarrier	等待事件可用，并协调消费者之间的依赖关系
BatchEventProcessor	驱动事件消费，管理消费进度
EventHandler	执行具体的业务逻辑
Sequence	记录生产或消费进度
```
这里需要特别注意：

BatchEventProcessor 不负责生产事件，也不负责存储事件，而是负责驱动消费过程。

它本身实现了 Runnable，通常由独立线程执行。

例如：
```
EventHandler<OrderEvent> handler = event -> {
processOrder(event);
};
BatchEventProcessor<OrderEvent> processor =
new BatchEventProcessor<>(
ringBuffer,
sequenceBarrier,
handler
);
Thread thread = new Thread(processor);
thread.start();
```
线程启动后，会进入 BatchEventProcessor.run()，不断获取可用事件并调用 handler。

这里的 OrderEvent 只是示例，实际使用时可以替换为订单、交易、账户变更等事件。

1.2 Batch 为什么叫 Batch？

BatchEventProcessor 中的 Batch，指的是一次获取一批可用事件，再逐个处理。

假设 RingBuffer 中已经发布了以下事件：
```
Sequence:   101  102  103  104  105  106
Event:       A    B    C    D    E    F
```
当前消费者的消费进度为：

consumerSequence = 100

如果生产者已经发布到 105，那么消费者可能一次获得：
```
101, 102, 103, 104, 105
```
然后依次执行：
```
handler.onEvent(event101, 101, false);
handler.onEvent(event102, 102, false);
handler.onEvent(event103, 103, false);
handler.onEvent(event104, 104, false);
handler.onEvent(event105, 105, true);
```
这里的第三个参数是 endOfBatch，表示当前事件是否为这一批中的最后一个事件。

不过要注意：

* 批次的大小取决于当前可用的连续事件数量。
* endOfBatch 表示当前处理批次的边界。
* 批量获取不代表并行处理，单个 BatchEventProcessor 默认仍然逐个调用事件处理器。

例如，最后一个事件可以触发批量写数据库、刷新缓冲区等操作。

⸻

二、核心类结构

Disruptor 3.x 中，BatchEventProcessor 的核心结构可以概括为：
```
public final class BatchEventProcessor<T>
implements EventProcessor {
private final DataProvider<T> dataProvider;
private final SequenceBarrier sequenceBarrier;
private final EventHandler<? super T> eventHandler;
private final Sequence sequence = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);
private final TimeoutHandler timeoutHandler;
private final BatchStartAware batchStartAware;
private final ExceptionHandler<? super T> exceptionHandler;
private volatile boolean running = false;
// ...
}
```
不同 Disruptor 版本的字段及具体实现可能有所不同，但核心设计基本一致。

下面逐个分析。

2.1 dataProvider
```
private final DataProvider<T> dataProvider;
```
负责根据序列号获取对应的事件。

典型调用：
```
T event = dataProvider.get(nextSequence);
```
在通常的 RingBuffer 使用场景中，dataProvider 就是 RingBuffer。

例如：
```
RingBuffer
|
+-- sequence 101 -> OrderEvent A
+-- sequence 102 -> OrderEvent B
+-- sequence 103 -> OrderEvent C
```
消费者通过序列号取得事件对象。

需要注意，RingBuffer 通常会预先创建并重复使用事件对象，因此：
```
T event = dataProvider.get(sequence);
```
并不是从队列中移除事件，而是获取对应槽位中的对象引用。

这也是 Disruptor 与传统链式节点队列的一个重要区别。

2.2 sequenceBarrier
```
private final SequenceBarrier sequenceBarrier;
```
这是消费流程中非常关键的一个组件。

消费者不能仅仅因为某个序列号已经分配，就认为对应事件已经可以安全读取。

生产者可能已经申请了序列号，但尚未完成事件写入和发布。

因此，消费者需要通过：
```
long availableSequence =
sequenceBarrier.waitFor(nextSequence);
```
等待目标序列可用。

这里的 waitFor() 不仅仅是简单等待，它还涉及：

* 等待目标序列对应的事件发布。
* 检查生产者是否已经完成相应序列的发布。
* 考虑消费者之间的依赖关系。
* 根据 WaitStrategy 决定等待行为。

例如，使用 BlockingWaitStrategy 时，等待期间可能涉及锁和条件变量。

使用 BusySpinWaitStrategy 时，则可能通过循环检查序列状态来等待。

SequenceBarrier 负责协调可用性和依赖关系，而不是负责调用业务处理器。

2.3 eventHandler
```
private final EventHandler<? super T> eventHandler;
```
这是具体的业务处理器。

例如：
```
public class OrderEventHandler
implements EventHandler<OrderEvent> {
@Override
public void onEvent(
OrderEvent event,
long sequence,
boolean endOfBatch) {
processOrder(event);
}
}
```
BatchEventProcessor 负责驱动消费，EventHandler 负责业务执行。

二者的职责分离，使得 Disruptor 可以复用统一的消费框架。

2.4 sequence
```
private final Sequence sequence =
new Sequence(Sequencer.INITIAL_CURSOR_VALUE);
```
sequence 表示这个消费者已经处理到哪里。

例如：
```
消费者已经处理完：
101 102 103 104 105
sequence = 105
```
它具有两个重要作用。

第一，记录消费进度。

消费者下一次通常从：
```
sequence.get() + 1
```
开始获取事件。

第二，参与生产者的容量控制。

在有消费者依赖的配置下，生产者可以通过跟踪消费者序列来判断哪些 RingBuffer 槽位已经可以安全复用。

例如，RingBuffer 容量为 1024，生产者不能无限制地覆盖尚未被必要消费者处理的事件。

因此，消费进度不仅是统计信息，也是 Disruptor 协调生产和消费的重要机制。

2.5 running
```
private volatile boolean running = false;
```
用于标识处理器是否正在运行。

它主要用于控制：

* 防止同一个处理器被重复启动。
* 判断当前消费循环是否应该继续执行。
* 配合 halt() 停止处理器。

为什么使用 volatile？

因为运行状态可能由一个线程修改，而由另一个线程读取。

例如：
```
Thread A                     Thread B
---------                    ---------
processor.run()              processor.halt()
读取 running                 running = false
进入消费循环                 发出停止信号
```
使用 volatile 可以保证对该字段的写入对其他线程可见。

但需要注意，volatile 只能解决可见性问题，并不意味着整个消费流程是线程安全的，也不能保证正在执行的业务逻辑立即停止。

⸻

三、核心方法：run()

run() 是整个 BatchEventProcessor 的入口。

它的核心工作可以归纳为：

1. 检查处理器是否已经启动。
2. 将处理器标记为运行状态。
3. 调用 SequenceBarrier 重置或检查中断状态。
4. 进入事件消费循环。
5. 处理运行过程中的异常。
6. 在退出时恢复处理器状态。

典型实现可以简化为：
```
@Override
public void run() {
if (!running.compareAndSet(false, true)) {
throw new IllegalStateException(
"Thread is already running"
);
}
sequenceBarrier.clearAlert();
notifyStart();
boolean notify = true;
long nextSequence = sequence.get() + 1L;
T event = null;
while (true) {
try {
long availableSequence =
sequenceBarrier.waitFor(nextSequence);
while (nextSequence <= availableSequence) {
event = dataProvider.get(nextSequence);
eventHandler.onEvent(
event,
nextSequence,
nextSequence == availableSequence
);
nextSequence++;
}
sequence.set(availableSequence);
} catch (final AlertException ex) {
if (!running) {
break;
}
} catch (final Throwable ex) {
handleExceptionFor(event, nextSequence, ex);
sequence.set(nextSequence);
nextSequence++;
}
}
notifyShutdown();
}
```
注意：这是一份用于理解执行流程的简化示意代码，并非某个固定版本的逐字源码。 不同版本在异常处理、批次通知、进度更新和退出清理方面可能有所不同。

接下来拆解它的关键逻辑。

3.1 为什么先读取 sequence，再加 1？
```
long nextSequence = sequence.get() + 1L;
```
假设当前消费进度为：

sequence = 100

那么下一个待处理的序列号就是：

nextSequence = 101

这是因为 sequence 记录的是已经完成的消费进度，而不是下一个待消费的序列号。

可以这样理解：

sequence       = 已完成位置
nextSequence   = 下一待处理位置

两者必须区分。

如果将 nextSequence 错误地初始化为 sequence.get()，就可能重复处理已经完成的事件。

3.2 为什么需要 waitFor()？
```
long availableSequence =
sequenceBarrier.waitFor(nextSequence);
```
假设：

nextSequence = 101

但生产者目前只发布到了 100。

消费者不能直接读取 101 对应的事件，因为这个槽位可能尚未写入有效数据。

这时，消费者需要等待。

当序列 101 可用后，waitFor(101) 才能返回满足条件的可用序列。

例如：
```
消费者请求：101
生产者已发布：100
|
v
消费者等待
生产者发布：101、102、103
|
v
消费者获得可用范围
availableSequence = 103
```
随后，消费者便可以处理 101、102、103。

需要注意，实际返回值还受到消费者依赖关系和连续可用序列的约束，不应简单理解为生产者当前的最大序列号。

3.3 为什么外层是 while 循环？
```
while (true) {
// 获取事件并处理
}
```
因为 BatchEventProcessor 通常是一个长期运行的消费者。

一次批量消费完成后，它不会退出，而是继续等待下一批事件。

整体过程是：
```
启动消费者
|
v
等待事件可用
|
v
获取可用序列范围
|
v
处理一批事件
|
v
更新消费进度
|
v
继续等待下一批事件
```
只有发生停止信号或其他需要终止的情况时，消费循环才会退出。

⸻

四、核心消费逻辑：批量处理事件

这是 BatchEventProcessor 最值得深入理解的部分。

典型逻辑如下：
```
long availableSequence =
sequenceBarrier.waitFor(nextSequence);
while (nextSequence <= availableSequence) {
T event = dataProvider.get(nextSequence);
eventHandler.onEvent(
event,
nextSequence,
nextSequence == availableSequence
);
nextSequence++;
}
sequence.set(availableSequence);
```
我们分步骤分析。

4.1 第一步：等待可用事件
```
long availableSequence =
sequenceBarrier.waitFor(nextSequence);
```
假设：
```
nextSequence = 101
availableSequence = 105
```
表示消费者可以处理 101 到 105 这一段连续可用的序列。

注意这里的 availableSequence 是一个批次的边界，不是批次大小。

批次大小为：
```
batchSize = availableSequence - nextSequence + 1
= 105 - 101 + 1
= 5
```
4.2 第二步：逐个获取事件
```
T event = dataProvider.get(nextSequence);
```
消费者依次获取：
```
101 -> Event A
102 -> Event B
103 -> Event C
104 -> Event D
105 -> Event E
```
RingBuffer 通常不会因为读取事件而将槽位移除。

消费者只是按照序列号访问对应的事件对象。

4.3 第三步：调用业务处理器
```
eventHandler.onEvent(
event,
nextSequence,
nextSequence == availableSequence
);
```
onEvent() 有三个参数：
```
参数	含义
event	当前事件对象
sequence	当前事件的序列号
endOfBatch	当前事件是否是本次批次的最后一个事件
```
假设本批次范围为 101～105：
```
Sequence	endOfBatch
101	false
102	false
103	false
104	false
105	true
```
为什么需要 endOfBatch？

因为业务处理器可以利用它进行批次级别的优化。

例如：
```

public class TradeEventHandler
implements EventHandler<TradeEvent> {
private final List<TradeEvent> buffer =
new ArrayList<>();
@Override
public void onEvent(
TradeEvent event,
long sequence,
boolean endOfBatch) {
buffer.add(event);
if (endOfBatch) {
flushToDatabase(buffer);
buffer.clear();
}
}
}
```

这样可以减少数据库写入次数。

不过，这段代码仅用于说明批次语义。真实实现还需要考虑事件对象复用、异步持有事件、异常后的缓冲区清理等问题。

特别注意：RingBuffer 中的事件对象可能被生产者后续复用。如果业务代码要在回调结束后继续使用事件内容，应该复制必要的数据，而不是长期保存事件对象引用。

4.4 第四步：推进 nextSequence

nextSequence++;

处理完序列 101 后：

nextSequence = 102

处理完序列 102 后：

nextSequence = 103

最终处理完 105：

nextSequence = 106

此时，下一次消费将从 106 开始。

4.5 第五步：更新消费者 Sequence

sequence.set(availableSequence);

本批次结束后：

availableSequence = 105

因此：

sequence = 105

这表示消费者已经处理完本批次，并将消费进度推进到 105。

这里有一个非常关键的设计：

消费循环中的局部变量 nextSequence 负责推进下一待处理位置，而 sequence 负责对外发布当前消费进度。

为什么不能每次处理完事件就更新 sequence？

因为批量更新可以减少共享状态的写入频率，降低缓存一致性通信的开销。

例如：
```
逐条更新：
101 -> sequence.set(101)
102 -> sequence.set(102)
103 -> sequence.set(103)
104 -> sequence.set(104)
105 -> sequence.set(105)
批量更新：
处理 101～105
|
v
sequence.set(105)
```
需要注意，这并不意味着所有版本都只在批次末尾更新一次。具体更新频率取决于实现版本及执行路径。

⸻

五、BatchEventProcessor 与 SequenceBarrier 的关系

这是理解 Disruptor 消费模型的重点。

前面分析 ProcessingSequenceBarrier 时，我们已经知道，它负责等待生产者或上游消费者发布所需的序列。

那么，为什么 BatchEventProcessor 还需要维护自己的 Sequence？

因为二者职责不同。

组件	负责什么
SequenceBarrier	判断消费者当前可以处理到哪里
BatchEventProcessor	实际获取和处理事件
BatchEventProcessor.sequence	对外发布消费者处理进度

举一个例子。

假设存在两个消费者：

                RingBuffer
                    |
             +------+------+
             |             |
             v             v
         Consumer A    Consumer B

A 和 B 都独立消费 RingBuffer 中的事件。

假设：
```
生产者发布进度：1000
Consumer A 进度：950
Consumer B 进度：800
```
如果生产者需要确保事件在被两个消费者处理之前不被覆盖，那么它必须考虑消费者中较慢的那个。

在这种配置下，消费者进度会影响生产者可申请的序列范围。

现在再看消费者依赖：
```
Producer
|
v
Consumer A
|
v
Consumer B
```
B 依赖 A，意味着 B 不能越过 A 尚未发布为可用的处理进度。

因此，B 的 SequenceBarrier 会结合 A 的进度来确定可处理范围。

这里要区分两种关系：

1. 生产者容量约束：消费者进度决定哪些 RingBuffer 槽位可以安全复用。
2. 消费者依赖关系：上游消费者的进度决定下游消费者可以处理到哪里。

这两种关系都通过 Sequence 等机制协调，但语义不同。

⸻

六、BatchEventProcessor 与 WaitStrategy 的关系

BatchEventProcessor 本身不直接实现等待策略，而是通过 SequenceBarrier 间接使用 WaitStrategy。

基本调用链：
```
BatchEventProcessor
|
v
SequenceBarrier.waitFor()
|
v
ProcessingSequenceBarrier
|
v
WaitStrategy.waitFor()
|
v
等待事件可用
```
不同的 WaitStrategy 会影响消费者等待事件的方式。

6.1 BlockingWaitStrategy

基本特点：

* 在需要等待时，使用锁和条件变量协调。
* 不需要一直占用 CPU 自旋。
* 适合对 CPU 消耗比较敏感的普通业务场景。
* 唤醒和线程调度可能带来额外延迟。

例如：
```
Consumer
|
v
等待事件
|
v
线程阻塞
|
| Producer 发布事件
v
被唤醒
|
v
继续处理
```
6.2 BusySpinWaitStrategy

基本特点：

* 消费者通过循环不断检查序列是否可用。
* 减少阻塞和线程唤醒开销。
* 可能降低部分场景下的等待延迟。
* 会持续消耗 CPU 资源。

例如：
```

while (cursor < nextSequence) {
// 不断检查事件是否可用
}
```

这只是概念示意，不是该策略的逐字源码。

6.3 YieldingWaitStrategy

基本特点：

* 先进行有限次数的自旋。
* 之后通过 Thread.yield() 主动让出执行机会。
* 在 CPU 消耗与等待延迟之间折中。

6.4 三种策略对比
```
维度	Blocking	Yielding	BusySpin
等待方式	阻塞等待	自旋后让出 CPU	持续自旋
CPU 消耗	较低	中等或较高	较高
潜在等待延迟	可能较高	较低	可能最低
线程调度依赖	较明显	中等	较少依赖主动阻塞
适用场景	普通业务、资源敏感场景	对延迟敏感的场景	极低延迟且 CPU 资源充足的场景
```
需要强调的是，实际延迟还受到 CPU 调度、缓存一致性、NUMA、事件处理耗时和系统负载等因素影响。不能仅凭策略名称就断言某种策略在所有场景中一定更快。

⸻

七、异常处理机制

生产消费框架不能只考虑正常执行，还必须考虑业务处理器抛出异常时如何继续运行。

假设：
```
@Override
public void onEvent(
OrderEvent event,
long sequence,
boolean endOfBatch) {
if (event.getOrderId() == 123L) {
throw new RuntimeException("process order failed");
}
processOrder(event);
}
```
如果消费过程中发生异常，BatchEventProcessor 需要决定如何处理。

典型处理逻辑是：
```
try {
eventHandler.onEvent(
event,
nextSequence,
endOfBatch
);
} catch (Throwable ex) {
exceptionHandler.handleEventException(
ex,
nextSequence,
event
);
}
```
这是简化示意，实际异常捕获与进度更新逻辑依版本而异。

7.1 ExceptionHandler 的作用

异常处理器一般提供三类回调：

* handleEventException()：处理事件消费过程中的异常。
* handleOnStartException()：处理启动回调中的异常。
* handleOnShutdownException()：处理关闭回调中的异常。

不同版本的接口细节可能略有差异。

例如：
```
public class OrderExceptionHandler
implements ExceptionHandler<OrderEvent> {
@Override
public void handleEventException(
Throwable ex,
long sequence,
OrderEvent event) {
log.error(
"Failed to process order event, sequence={}",
sequence,
ex
);
}
@Override
public void handleOnStartException(Throwable ex) {
log.error("Processor start failed", ex);
}
@Override
public void handleOnShutdownException(Throwable ex) {
log.error("Processor shutdown failed", ex);
}
}
```
7.2 异常后会不会重新消费同一个事件？

不能简单地认为 BatchEventProcessor 会自动重试失败的事件。

这取决于版本中的异常处理路径和具体配置。

很多常见实现会在事件处理失败后调用异常处理器，然后推进消费位置，继续处理后续事件。

如果业务要求失败事件必须重试，就需要明确设计重试机制，例如：

* 在处理器内部进行有限次数重试。
* 将失败事件写入重试队列。
* 使用延迟重试或死信队列。
* 记录失败事件并由补偿任务处理。
* 在业务层实现幂等和状态恢复。

对于交易所的订单、成交、资产变更等场景，尤其不能仅仅记录异常日志就认为问题已经解决。

假设资产变更事件处理失败：
```
事件 101：成功
事件 102：失败
事件 103：成功
```
如果消费者继续推进到 103，而 102 没有被可靠记录或补偿，就可能产生业务数据不一致。

因此，需要将 Disruptor 的消费进度管理 与 业务可靠性保证 区分开来。

⸻

八、halt() 如何停止消费者？

BatchEventProcessor 提供了停止处理器的机制，典型方法是：
```
@Override
public void halt() {
running = false;
sequenceBarrier.alert();
}
```
这是简化示意。

为什么除了修改 running，还需要调用：

sequenceBarrier.alert();

因为消费者可能正在等待事件。

例如：
```
Consumer Thread
|
v
sequenceBarrier.waitFor()
|
v
等待新事件
```
此时，如果外部线程只执行：

running = false;

消费者未必能立即退出。

尤其当消费者阻塞在等待策略内部时，它需要一种机制来结束等待。

alert() 的作用就是通知等待中的消费者检查中断或停止状态。

消费线程收到提醒后，会在相应路径中抛出 AlertException 或检查到停止标志，从而退出消费循环。

需要注意：

halt() 通常不意味着正在执行的 onEvent() 会立即中断。

如果业务处理器正在执行一个耗时的数据库调用，那么停止请求通常要等到当前调用返回后才能得到处理。

另外，halt() 也不等同于：

* 等待所有业务任务执行完毕。
* 自动处理所有未消费事件。
* 保证所有业务数据已经持久化。

如果需要优雅关闭，通常还要配合线程生命周期管理、业务刷盘以及必要的等待机制。

⸻

九、BatchEventProcessor 为什么不能随便更新 Sequence？

这是一个值得重点理解的设计问题。

假设：

nextSequence = 101
availableSequence = 105

消费者正在处理 101～105。

正常情况下：
```
处理 101
处理 102
处理 103
处理 104
处理 105
更新 sequence = 105
```
假设事件 103 的处理抛出了异常。

此时会出现一个重要问题：

事件 103 到底算不算已经处理完成？

答案取决于业务异常处理策略，而不是仅仅取决于 Sequence 的数值。

例如：

情况 A：允许跳过失败事件
```
101 成功
102 成功
103 失败，记录异常
104 成功
105 成功
```
如果系统明确允许失败事件被跳过，那么消费者可以在完成相应异常处理后继续推进进度。

情况 B：失败事件必须成功处理
```
101 成功
102 成功
103 失败
```
如果业务要求严格保证每个事件都被成功处理，就不能在未解决 103 的失败问题时，随意把消费进度推进到 105。

否则，上层系统可能认为：

101～105 全部处理完成

但实际上 103 并没有完成业务操作。

不过，也不能直接把 Disruptor 的 Sequence 等同于业务事务提交位置。某些应用会将事件可靠地写入外部重试机制后再推进消费进度，而另一些应用则采用不同的恢复策略。

因此，在设计高可靠消费链路时，必须明确：

1. 事件处理失败的定义。
2. 异常处理器何时认为失败已被妥善处理。
3. 哪些情况下允许推进消费进度。
4. 如何保证重试、补偿和幂等。
5. 如何处理进程崩溃后尚未完成的业务操作。

⸻

十、BatchEventProcessor 的性能特点

理解它的实现之后，可以进一步分析为什么 Disruptor 适合高性能事件处理场景。

10.1 预分配事件对象

RingBuffer 通常预先创建并重复使用事件对象。

这减少了运行时频繁创建和销毁队列节点的需求。

不过，业务处理器仍然可能因为装箱、集合操作、日志拼接等操作产生额外对象分配。

因此，使用 Disruptor 不代表整个消费链路天然没有 GC 压力。

10.2 批量处理

一次获取多个连续可用事件，能够减少重复等待和部分共享状态更新的开销。

此外，endOfBatch 允许业务处理器在批次结束时执行批量操作。

例如：
```
单条写入：
Event 101 -> DB write
Event 102 -> DB write
Event 103 -> DB write
Event 104 -> DB write
批量写入：
Event 101 ┐
Event 102 ├──> Batch DB write
Event 103 ┤
Event 104 ┘
```
这通常可以减少数据库交互次数，但效果取决于批量大小、数据库接口和业务处理逻辑。

10.3 减少不必要的队列节点分配

与某些基于链表节点的队列实现相比，RingBuffer 通过固定容量的数组和序列号管理事件槽位。

它不需要为每个事件都创建新的链表节点。

10.4 单消费者的顺序处理

一个 BatchEventProcessor 通常由一个线程驱动，并按照序列顺序调用 EventHandler。

因此，它适合需要维持消费顺序的业务。

但要注意：

如果业务处理器自己启动异步任务，那么业务任务实际完成的顺序可能与事件的调用顺序不同。

例如：
```
@Override
public void onEvent(
OrderEvent event,
long sequence,
boolean endOfBatch) {
executor.submit(() -> processOrder(event));
}
```

此时，BatchEventProcessor 只是按序提交任务，并不保证 processOrder() 的实际执行和完成顺序。

如果业务要求严格顺序，必须额外设计执行模型。

⸻

十一、把整个消费过程串起来

现在通过一个完整例子，理解从生产者发布事件到消费者推进进度的过程。

假设：
```
RingBuffer 容量：1024
消费者初始进度：100
生产者发布：
101、102、103、104、105
```
第一步：消费者计算下一个序列号

long nextSequence = sequence.get() + 1L;

得到：

nextSequence = 101

第二步：等待事件可用

long availableSequence =
sequenceBarrier.waitFor(101);

假设返回：

availableSequence = 105

第三步：获取事件

OrderEvent event =
dataProvider.get(nextSequence);

依次获取 101～105 对应的事件对象。

第四步：调用业务处理器

eventHandler.onEvent(
event,
nextSequence,
nextSequence == availableSequence
);

其中只有序列 105 的 endOfBatch 为 true。

第五步：推进局部消费位置

nextSequence++;

最终：

nextSequence = 106

第六步：发布消费进度

sequence.set(105);

消费者的对外进度变为：

sequence = 105

第七步：进入下一轮

下一轮开始时：

nextSequence = 106

消费者继续等待序列 106 及其后续可用事件。

完整流程如下：

                run()
                  |
                  v
       nextSequence = sequence + 1
                  |
                  v
       sequenceBarrier.waitFor()
                  |
                  v
       获取 availableSequence
                  |
                  v
       +----------------------+
       | nextSequence <=     |
       | availableSequence?  |
       +----------------------+
             |          |
            Yes         No
             |          |
             v          |
      dataProvider.get() |
             |          |
             v          |
      eventHandler       |
        .onEvent()       |
             |          |
             v          |
       nextSequence++   |
             |          |
             +----------+
                  |
                  v
       更新消费者 Sequence
                  |
                  v
           进入下一轮

这里的流程图是概念示意：实际实现可能在异常、超时和进度更新等路径中采用不同的控制逻辑。

⸻

十二、面试常见问题

结合 Java 并发和高性能系统设计，下面这些问题值得掌握。

Q1：BatchEventProcessor 的核心职责是什么？

回答：

BatchEventProcessor 是 Disruptor 的核心消费执行器。它通过 SequenceBarrier 等待事件可用，通过 DataProvider 获取事件，调用 EventHandler 处理事件，并维护自己的消费 Sequence。

它将事件可用性协调、消费循环和业务处理逻辑分离开来。

Q2：BatchEventProcessor 为什么需要 SequenceBarrier？

回答：

生产者分配序列号和发布事件是不同的阶段，消费者不能只根据已分配的序列号判断事件已经可以读取。

SequenceBarrier 负责等待目标序列可用，并考虑生产者发布状态和消费者依赖关系。

Q3：BatchEventProcessor 为什么使用 Sequence？

回答：

Sequence 记录消费者的处理进度，并将该进度发布给 Disruptor 的其他组件。

在生产者容量控制中，消费进度可以用于判断槽位何时可以安全复用；在消费者依赖关系中，上游消费者的进度也可以约束下游消费者。

Q4：为什么需要 endOfBatch？

回答：

endOfBatch 用于告诉业务处理器当前事件是否是本次可用批次的最后一个事件。

业务可以在批次末尾执行批量数据库写入、缓冲区刷新等操作，减少单条处理带来的额外开销。

但它不意味着业务处理器可以忽略事件的正确性，也不保证批量操作天然具有事务性。

Q5：BatchEventProcessor 是并行消费的吗？

回答：

单个 BatchEventProcessor 通常由一个线程驱动，批次中的事件仍然逐个调用 EventHandler。

如果需要并行处理，可以使用多个消费者、工作池模式或其他并发执行设计，但必须明确事件分发、顺序保证和消费进度语义。

Q6：BatchEventProcessor 如何停止？

回答：

通常通过 halt() 修改运行状态，并调用 SequenceBarrier.alert() 通知等待中的消费者检查停止状态。

但这不代表正在执行的业务回调会立即被中断，也不代表所有未处理事件都会自动完成。

Q7：消费事件失败后，Disruptor 会自动重试吗？

回答：

不能假设它会自动重试。常见实现会将异常交给 ExceptionHandler，具体如何推进消费进度取决于实现版本及异常处理路径。

如果业务要求可靠重试，需要额外设计重试、补偿、幂等和故障恢复机制。

⸻

十三、总结：抓住三个核心点

理解 BatchEventProcessor，最重要的是掌握三个方面。

第一，事件可用性。

sequenceBarrier.waitFor(nextSequence);

解决的是消费者什么时候可以安全地获取目标事件。

第二，事件消费。

eventHandler.onEvent(event, sequence, endOfBatch);

解决的是如何将事件交给业务处理器，以及如何利用批次边界优化业务处理。

第三，消费进度。

sequence.set(availableSequence);

解决的是如何向 Disruptor 的其他组件发布消费者进度。

将三者串起来，就是：
```
等待事件可用
|
v
获取一批事件
|
v
逐个执行业务处理
|
v
推进消费进度
|
v
继续消费
```
对于你正在研究的 Disruptor 源码，建议下一步重点分析 BatchEventProcessor 如何与 ProcessingSequenceBarrier、Sequence 和 MultiProducerSequencer 配合工作。

这几个类串起来之后，就能从源码层面完整理解 Disruptor 的核心链路：生产者如何发布事件、消费者如何识别可用事件，以及消费进度如何反过来影响生产者的容量控制。