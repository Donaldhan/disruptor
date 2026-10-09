可以。结合你前面一直在研究的 Disruptor 4.x + Java 17，下面给你一个比较接近实际开发的批量消费完整示例。

这里重点演示：

Producer 连续生产事件 → Consumer 按批次累积 → 达到 batchSize 或 endOfBatch → 一次性批量处理。

1. 完整代码
```java
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.YieldingWaitStrategy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
public class DisruptorBatchConsumerDemo {
    /**
     * =========================
     * 1. Event
     * =========================
     */
    static class OrderEvent {
        long orderId;
        String symbol;
        long price;
        long quantity;
        void set(long orderId, String symbol, long price, long quantity) {
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
     * =========================
     * 2. Batch Consumer
     * =========================
     *
     * 每次最多处理 BATCH_SIZE 条。
     *
     * 两种情况下触发 flush：
     *
     * ① batchSize 达到上限
     * ② endOfBatch == true
     */
    static class BatchOrderConsumer implements EventHandler<OrderEvent> {
        private static final int BATCH_SIZE = 5;
        private final List<OrderEvent> batch =
                new ArrayList<>(BATCH_SIZE);
        @Override
        public void onEvent(
                OrderEvent event,
                long sequence,
                boolean endOfBatch) {
            batch.add(event);
            System.out.println(
                    "收到 event, sequence=" + sequence +
                    ", endOfBatch=" + endOfBatch
            );
            /*
             * 条件1：
             * 达到最大批量
             */
            if (batch.size() >= BATCH_SIZE) {
                flush();
            }
            /*
             * 条件2：
             * 当前没有更多事件需要处理
             *
             * 注意：
             * 如果上面已经 flush，这里不能再次 flush。
             */
            else if (endOfBatch) {
                flush();
            }
        }
        private void flush() {
            if (batch.isEmpty()) {
                return;
            }
            System.out.println(
                    "========== BATCH PROCESS =========="
            );
            System.out.println(
                    "batch size = " + batch.size()
            );
            /*
             * 模拟批量 DB / ES / MQ 操作
             */
            batchInsert(batch);
            /*
             * 清空 batch
             *
             * 注意：
             * 这里不能简单理解成销毁 Event。
             * RingBuffer 中的 Event 是复用的。
             */
            batch.clear();
            System.out.println(
                    "===================================\n"
            );
        }
        private void batchInsert(List<OrderEvent> events) {
            System.out.println(
                    "Batch insert " +
                    events.size() +
                    " orders"
            );
            for (OrderEvent event : events) {
                System.out.println(
                        "    -> " + event
                );
            }
        }
    }
    /**
     * =========================
     * 3. Producer
     * =========================
     */
    static class OrderProducer {
        private final RingBuffer<OrderEvent> ringBuffer;
        OrderProducer(RingBuffer<OrderEvent> ringBuffer) {
            this.ringBuffer = ringBuffer;
        }
        public void publish(
                long orderId,
                String symbol,
                long price,
                long quantity) {
            long sequence = ringBuffer.next();
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
                /*
                 * 一定要 publish
                 */
                ringBuffer.publish(sequence);
            }
        }
    }
    /**
     * =========================
     * 4. Main
     * =========================
     */
    public static void main(String[] args)
            throws InterruptedException {
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
        /*
         * Disruptor 4.x
         *
         * Java 17
         */
        Disruptor<OrderEvent> disruptor =
                new Disruptor<>(
                        OrderEvent::new,
                        ringBufferSize,
                        threadFactory,
                        ProducerType.SINGLE,
                        new YieldingWaitStrategy()
                );
        /*
         * 注册 Batch Consumer
         */
        disruptor.handleEventsWith(
                new BatchOrderConsumer()
        );
        /*
         * 启动
         */
        RingBuffer<OrderEvent> ringBuffer =
                disruptor.start();
        OrderProducer producer =
                new OrderProducer(ringBuffer);
        /*
         * =========================
         * 模拟生产 12 个订单
         * =========================
         */
        for (long i = 1; i <= 12; i++) {
            producer.publish(
                    i,
                    "BTCUSDT",
                    100000 + i,
                    i * 10
            );
        }
        /*
         * 等待消费完成
         */
        Thread.sleep(1000);
        /*
         * 关闭
         */
        disruptor.shutdown();
    }
}
```
⸻

2. 这个例子的核心

真正重要的是 Consumer：
```
@Override
public void onEvent(
        OrderEvent event,
        long sequence,
        boolean endOfBatch) {
    batch.add(event);
    if (batch.size() >= BATCH_SIZE) {
        flush();
    }
    else if (endOfBatch) {
        flush();
    }
}
```
可以理解成：

                 Disruptor
                    │
                    ▼
              EventProcessor
                    │
          ┌─────────┴─────────┐
          │                   │
      Event 1             Event 2
          │                   │
          ▼                   ▼
       batch.add()         batch.add()
          │                   │
          └─────────┬─────────┘
                    ▼
                 batch
              [1,2,3,4,5]
                    │
                    ▼
              batch.size >= 5
                    │
                    ▼
                  flush()
                    │
                    ▼
              DB Bulk Insert

⸻

3. endOfBatch 到底是什么？

这是这个问题里最容易误解的地方。

例如 RingBuffer 当前有：
```
Sequence
100
101
102
103
104
```
Consumer 开始消费。

如果一次处理到了：
```
100
101
102
103
104
```
而 104 是当前这一轮能够看到的最后一个事件，那么：

onEvent(event104, 104, true)

可能得到：

endOfBatch = true

于是：

if (endOfBatch) {
    flush();
}

可以把当前积累的数据一次性提交。

⸻

4. 为什么还需要 BATCH_SIZE？

因为不能只依赖：

endOfBatch

实际系统通常采用：

          Event
            │
            ▼
        batch.add
            │
       ┌────┴────┐
       │         │
 size >= 500   endOfBatch
       │         │
       └────┬────┘
            ▼
          flush

例如：

private static final int BATCH_SIZE = 500;

那么：

情况 A：流量很大
```
Event 1
Event 2
...
Event 500
      ↓
size == 500
      ↓
flush()
```
不会等很久。

⸻

情况 B：流量很小

例如只来了：
```
Event 1
Event 2
Event 3
```
然后当前没有更多事件：

Event 3
  ↓
endOfBatch = true
  ↓
flush()

不会因为等不到 500 条而一直不提交。

所以实际系统经常采用：

Batch Size + End Of Batch / Timeout

⸻

5. 但是这个 Demo 有一个重要问题

上面的代码是为了理解 endOfBatch。

生产环境不能直接这么写：

batch.add(event);

然后长期持有 event 引用。

因为 Disruptor 的 Event 是：

RingBuffer 中预分配并循环复用的对象。

例如：
```
RingBuffer
slot 0 → OrderEvent
slot 1 → OrderEvent
slot 2 → OrderEvent
...
```
生产者不断循环：
```
sequence 0 → slot 0
sequence 1024 → slot 0
sequence 2048 → slot 0
```
所以 Consumer 如果把：

event

保存到自己的 List 中，而生产者已经可以复用这个 slot，那么这个对象里的数据可能已经发生变化。

⸻

6. 生产环境应该怎么做？

通常不要保存 OrderEvent 对象本身，而是：

方案一：复制需要的数据

例如：
```java
static class OrderRecord {
    long orderId;
    String symbol;
    long price;
    long quantity;
    OrderRecord(OrderEvent event) {
        this.orderId = event.orderId;
        this.symbol = event.symbol;
        this.price = event.price;
        this.quantity = event.quantity;
    }
}

Consumer：

private final List<OrderRecord> batch = new ArrayList<>(500);
@Override
public void onEvent(
        OrderEvent event,
        long sequence,
        boolean endOfBatch) {
    batch.add(new OrderRecord(event));
    if (batch.size() >= BATCH_SIZE || endOfBatch) {
        flush();
    }
}
```
这样：
```
RingBuffer Event
      │
      │ copy
      ▼
OrderRecord
      │
      ▼
Batch List
      │
      ▼
Bulk Insert
```
更加安全。

⸻

7. 实际项目里最典型的场景

比如你的 Bybit Asset / Trade / Historical Data 场景：
```
Kafka
  │
  │ poll()
  ▼
500 TradeEvent
  │
  ▼
Dispatcher
  │
  │ AccountId Hash
  ▼
Disruptor Shard
  │
  ▼
Batch Consumer
  │
  ├── 500 events
  │
  ▼
Batch Processing
  │
  ├── ES Bulk
  ├── HBase Batch Put
  ├── DB Batch Insert
  └── MQ Batch Produce
```
例如：
```
if (batch.size() >= 500 || endOfBatch) {
    elasticsearch.bulk(batch);
    batch.clear();
}
```

这比：
```
for (Order order : orders) {
    elasticsearch.insert(order);
}
```
通常更加高效。

⸻

8. Kafka 和 Disruptor 的 Batch 是两回事

这个面试非常容易问。

Kafka
```
ConsumerRecords<String, Order> records =
        consumer.poll(Duration.ofMillis(100));
```
这是：

Batch Fetch / Batch Poll

一次从 Kafka 拉回来一批消息。

⸻

Disruptor
```
onEvent(
    Event event,
    long sequence,
    boolean endOfBatch
)
```
本身是：

Event-by-Event Delivery

但是可以利用：

endOfBatch

实现：

Batch Processing / Batch Flush

所以：
```
Kafka
    │
    │ poll()
    ▼
Batch Fetch
    │
    ▼
Disruptor
    │
    │ onEvent()
    ▼
Batch Accumulate
    │
    │ endOfBatch / size
    ▼
Batch Process
```
这是实际系统里非常常见的一种组合。

⸻

9. 面试可以这样回答

如果面试官问：

Disruptor 支持批量消费吗？

可以回答：

Disruptor 本身的 EventHandler 是逐事件回调的，并不是像 Kafka poll() 那样直接返回一个消息集合。但是 onEvent() 提供了 endOfBatch 参数，可以让 Consumer 感知当前批次是否处理到了末尾，因此可以在 Consumer 内部累积事件，并结合 batchSize 或 endOfBatch 触发批量处理，例如 ES Bulk、DB Batch Insert、HBase Batch Put 等。实际生产中通常会采用“批量大小 + 时间/批次结束”的策略，避免高流量下批次过大，也避免低流量下数据长时间滞留。

如果再结合你的交易系统经验，可以进一步说：
```
Kafka Batch Poll
      ↓
AccountId Sharding
      ↓
Disruptor
      ↓
Account State Update
      ↓
Batch Flush
      ↓
ES / HBase / DB
```
这个回答会比单纯说“Disruptor 支持 Batch”更加准确。