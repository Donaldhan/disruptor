package com.lmax.disruptor.examples.learn;

import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.YieldingWaitStrategy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 批量消费模式
 */
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