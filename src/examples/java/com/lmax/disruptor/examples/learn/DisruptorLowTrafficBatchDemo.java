package com.lmax.disruptor.examples.learn;

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

/**
 * 低流量模式，加入超时机制
 */
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