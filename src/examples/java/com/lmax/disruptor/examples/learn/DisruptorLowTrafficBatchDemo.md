可以。低流量场景的核心是：

Batch Size + Timeout，谁先达到谁触发 flush。

例如：

最多 5 条
或者最多等待 1000ms
谁先到谁提交

不过 Disruptor 的 onEvent() 本身不是定时器，所以低流量下不能只依赖 endOfBatch。一个比较实际的实现是：Consumer 正常按事件累积，同时由独立的定时任务触发超时 flush。

下面给你一个 Java 17 + Disruptor 4.x 的完整示例。

⸻

1. 完整实现
```java
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.YieldingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
public class DisruptorLowTrafficBatchDemo {
/**
* =========================================================
* Event
* =========================================================
*/
static class OrderEvent {
long orderId;
String symbol;
long price;
long quantity;
void set(
long orderId,
String symbol,
long price,
long quantity) {
this.orderId = orderId;
this.symbol = symbol;
this.price = price;
this.quantity = quantity;
}
@Override
public String toString() {
return "OrderEvent{" +
"orderId=" + orderId +
", symbol='" + symbol + '\'' +
", price=" + price +
", quantity=" + quantity +
'}';
}
}
/**
* =========================================================
* Batch Consumer
*
* 触发条件：
*
* 1. batch.size >= MAX_BATCH_SIZE
* 2. 超过 MAX_WAIT_MILLIS
*
* 两者谁先发生谁 flush。
* =========================================================
*/
static class BatchOrderConsumer
implements EventHandler<OrderEvent> {
/**
* 最大批量
*/
private static final int MAX_BATCH_SIZE = 5;
/**
* 最大等待时间
*/
private static final long MAX_WAIT_MILLIS = 1000;
/**
* 当前 batch
*/
private final List<OrderRecord> batch =
new ArrayList<>(MAX_BATCH_SIZE);
/**
* 第一个 Event 进入 batch 的时间
*/
private volatile long firstEventTime = 0;
/**
* EventProcessor 消费线程
*/
@Override
public synchronized void onEvent(
OrderEvent event,
long sequence,
boolean endOfBatch) {
/*
* 第一个 Event 进入 batch
*/
if (batch.isEmpty()) {
firstEventTime =
System.currentTimeMillis();
}
/*
* 注意：
* RingBuffer 的 Event 会被复用。
*
* 所以不能直接保存 OrderEvent。
*/
batch.add(new OrderRecord(event));
System.out.println(
"收到 event: sequence=" +
sequence +
", orderId=" +
event.orderId +
", endOfBatch=" +
endOfBatch
);
/*
* 高流量：
* batch 满了立即 flush
*/
if (batch.size() >= MAX_BATCH_SIZE) {
flush("BATCH_SIZE");
}
}
/**
* =====================================================
* Timeout Thread 调用
* =====================================================
*/
public synchronized void flushIfTimeout() {
if (batch.isEmpty()) {
return;
}
long now =
System.currentTimeMillis();
long elapsed =
now - firstEventTime;
if (elapsed >= MAX_WAIT_MILLIS) {
flush("TIMEOUT");
}
}
/**
* =====================================================
* Flush
* =====================================================
*/
private void flush(String reason) {
if (batch.isEmpty()) {
return;
}
System.out.println();
System.out.println(
"========== FLUSH =========="
);
System.out.println(
"reason = " + reason
);
System.out.println(
"batch size = " + batch.size()
);
/*
* 模拟 DB / ES / HBase Bulk
*/
batchInsert(batch);
batch.clear();
firstEventTime = 0;
System.out.println(
"===========================\n"
);
}
/**
* 模拟批量数据库操作
*/
private void batchInsert(
List<OrderRecord> records) {
System.out.println(
"Batch Insert:"
);
for (OrderRecord record : records) {
System.out.println(
"    " + record
);
}
}
}
/**
* =========================================================
* RingBuffer Event → 独立对象
* =========================================================
*/
static class OrderRecord {
final long orderId;
final String symbol;
final long price;
final long quantity;
OrderRecord(OrderEvent event) {
this.orderId = event.orderId;
this.symbol = event.symbol;
this.price = event.price;
this.quantity = event.quantity;
}
@Override
public String toString() {
return "OrderRecord{" +
"orderId=" + orderId +
", symbol='" + symbol + '\'' +
", price=" + price +
", quantity=" + quantity +
'}';
}
}
/**
* =========================================================
* Producer
* =========================================================
*/
static class OrderProducer {
private final RingBuffer<OrderEvent> ringBuffer;
OrderProducer(
RingBuffer<OrderEvent> ringBuffer) {
this.ringBuffer = ringBuffer;
}
public void publish(
long orderId,
String symbol,
long price,
long quantity) {
long sequence =
ringBuffer.next();
try {
OrderEvent event =
ringBuffer.get(sequence);
event.set(
orderId,
symbol,
price,
quantity
);
} finally {
ringBuffer.publish(sequence);
}
}
}
/**
* =========================================================
* Main
* =========================================================
*/
public static void main(String[] args)
throws Exception {
int ringBufferSize = 1024;
AtomicInteger threadId =
new AtomicInteger();
ThreadFactory threadFactory =
runnable ->
new Thread(
runnable,
"disruptor-" +
threadId.getAndIncrement()
);
/**
* Disruptor 4.x
*/
Disruptor<OrderEvent> disruptor =
new Disruptor<>(
OrderEvent::new,
ringBufferSize,
threadFactory,
ProducerType.SINGLE,
new YieldingWaitStrategy()
);
/**
* 创建 Consumer
*/
BatchOrderConsumer consumer =
new BatchOrderConsumer();
/**
* 注册 Consumer
*/
disruptor.handleEventsWith(
consumer
);
/**
* 启动
*/
RingBuffer<OrderEvent> ringBuffer =
disruptor.start();
OrderProducer producer =
new OrderProducer(ringBuffer);
/**
* =====================================================
* Timeout Scheduler
*
* 每 100ms 检查一次：
*
* 当前 batch 是否已经等待超过 1 秒
* =====================================================
*/
ScheduledExecutorService scheduler =
Executors.newSingleThreadScheduledExecutor(
runnable ->
new Thread(
runnable,
"batch-timeout-checker"
)
);
scheduler.scheduleAtFixedRate(
consumer::flushIfTimeout,
100,
100,
TimeUnit.MILLISECONDS
);
/**
* =====================================================
* 模拟低流量
*
* 每 300ms 产生一个 Event
*
* MAX_BATCH_SIZE = 5
* MAX_WAIT = 1000ms
*
* 所以通常不会等到 5 条。
* =====================================================
*/
for (long i = 1; i <= 10; i++) {
producer.publish(
i,
"BTCUSDT",
100000 + i,
i * 10
);
Thread.sleep(300);
}
/**
* 等待最后一批 timeout flush
*/
Thread.sleep(2000);
/**
* 关闭
*/
scheduler.shutdown();
disruptor.shutdown();
}
}
```
⸻

2. 运行效果

由于这里：

MAX_BATCH_SIZE = 5;

但是 Producer：

Thread.sleep(300);

也就是：
```
300ms
300ms
300ms
300ms
...
```
所以第一批大概是：
```
0ms      Event 1
300ms    Event 2
600ms    Event 3
900ms    Event 4
1200ms   Event 5
```
但是我们设置：

MAX_WAIT = 1000ms

所以不会等到 5 条。

可能出现：

========== FLUSH ==========
reason = TIMEOUT
batch size = 4
============================

然后继续：
```
Event 5
Event 6
Event 7
Event 8
...
```
再次 timeout。

⸻

3. 整个机制

可以把它理解成一个小型 Buffer：

                 Event
                   │
                   ▼
             ┌───────────┐
             │   Batch   │
             │           │
             │ 1         │
             │ 2         │
             │ 3         │
             │ 4         │
             └─────┬─────┘
                   │
          ┌────────┴────────┐
          │                 │
       size >= 5        elapsed >= 1s
          │                 │
          └────────┬────────┘
                   ▼
                 flush
                   │
                   ▼
             DB / ES / HBase

所以这是：

Size-based + Time-based batching

⸻

4. 为什么需要 synchronized？

这里有两个线程会操作 batch：

Disruptor Consumer Thread
│
└── onEvent()
│
▼
batch
Scheduler Thread
│
└── flushIfTimeout()
│
▼
batch

因此：

public synchronized void onEvent(...)

和：

public synchronized void flushIfTimeout()

保证两个线程不会同时修改：

batch

否则可能发生：
```
Consumer Thread             Timer Thread
batch.add()
batch.clear()
batch.size()
```
导致并发问题。

⸻

5. 但是生产环境还有一个更重要的问题

上面的实现适合演示机制。

真正生产环境我更推荐：
```
Disruptor Consumer
│
▼
Batch Buffer
│
▼
独立 Batch Worker
│
├── Size Trigger
│
└── Timer Trigger
│
▼
Bulk IO
```
尤其是：
```
ES Bulk
HBase Batch
JDBC Batch
Kafka Batch Produce
```
这些 I/O 操作不要直接长时间阻塞 Disruptor Consumer Thread。

否则：
```
Disruptor Consumer
│
├── Event 1
├── Event 2
├── Event 3
│
└── ES Bulk
│
│ 50ms
▼
Consumer 卡住
```
会导致 RingBuffer 后面的事件无法及时消费。

⸻

6. 如果放到你的交易/Asset 系统

更接近你的场景应该是：
```
Kafka
│
│ poll 500
▼
Dispatcher
│
│ AccountId Hash
▼
Disruptor Shard
│
│ Event by Event
▼
AccountState Consumer
│
├──────────────┐
│              │
▼              ▼
State Update   Batch Buffer
│
┌─────┴─────┐
│           │
500条       10ms
│           │
└─────┬─────┘
▼
Batch Flush
│
┌───────┼───────┐
▼       ▼       ▼
ES      HBase    DB
```
这里要特别区分：

AccountState 本身的状态更新通常不能简单为了 Batch 而打乱同一 Account 的事件顺序。

例如：
```
Account A
Event 1: +100
Event 2: -30
Event 3: +50
```
必须保证：
```
+100
↓
-30
↓
+50
```
而批量化更适合发生在：
```
State Update
↓
Persistence / Index / External IO
```
这一层。

⸻

面试一句话

如果面试官问：

低流量情况下 Disruptor 如何实现批量消费？

可以直接回答：

Disruptor 的 endOfBatch 不能作为可靠的业务批量信号。生产环境通常在 Consumer 侧维护 Batch Buffer，同时设置两个触发条件：达到最大 Batch Size 立即 flush，以及第一个消息进入 Batch 后超过最大等待时间由定时任务触发 flush，也就是典型的 Size + Time 双触发策略。这样高流量保证吞吐，低流量保证延迟上界。