package com.jetlang.remote.server.example;

import org.jetlang.channels.*;
import org.jetlang.core.Callback;
import org.jetlang.core.DisposingExecutor;
import org.jetlang.core.SynchronousDisposingExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * User: mrettig
 * Date: 4/5/11
 * Time: 4:18 PM
 */
public class EventAssert<T> {

    public final CountDownLatch latch;
    public final AtomicInteger receiveCount = new AtomicInteger(0);
    public final LinkedBlockingQueue<T> received = new LinkedBlockingQueue<T>();
    private final Channel<T> onRcv = new MemoryChannel<T>();
    private final int expected;

    public EventAssert(int expected) {
        this.expected = expected;
        this.latch = new CountDownLatch(expected);
    }

    public Subscribable<T> asSubscribable() {
        Callback<T> r = createCallback();
        return new ChannelSubscription<T>(new SynchronousDisposingExecutor(), r);
    }

    public void subscribe(Subscriber<T> channel, DisposingExecutor exec) {
        Callback<T> r = createCallback();
        channel.subscribe(exec, r);

    }

    public void subscribe(Subscriber<T> channel) {
        Callback<T> r = createCallback();
        channel.subscribe(new SynchronousDisposingExecutor(), r);
    }

    private Callback<T> createCallback() {
        return new Callback<T>() {
            public void onMessage(T message) {
                receiveCount.incrementAndGet();
                latch.countDown();
                try {
                    received.put(message);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                onRcv.publish(message);
            }
        };
    }

    public void assertEvent() {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
            assertEquals(expected, receiveCount.get());
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static <T> EventAssert<T> expect(int expected, Subscriber<T> connected) {
        EventAssert<T> eventSink = new EventAssert<T>(expected);
        eventSink.subscribe(connected);
        return eventSink;
    }

    public T takeFromReceived() {
        try {
            return received.take();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public void onMessage(Callback<T> onTopic) {
        onRcv.subscribe(new SynchronousDisposingExecutor(), onTopic);
    }
}