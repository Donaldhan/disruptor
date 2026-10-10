


```java
com.lmax.disruptor.RingBuffer.publish(long)
com.lmax.disruptor.SingleProducerSequencer.publish(long)
@Override
public void publish(final long sequence)
{
cursor.set(sequence);
waitStrategy.signalAllWhenBlocking();
}

```
