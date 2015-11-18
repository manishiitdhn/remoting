package org.jetlang.remote.acceptor;

import org.jetlang.core.Disposable;
import org.jetlang.fibers.Fiber;
import org.jetlang.remote.core.ErrorHandler;
import org.jetlang.remote.core.MsgTypes;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class JetlangStreamSession extends JetlangBaseSession {

    private final MessageStreamWriter socket;
    private final Fiber sendFiber;
    private final ErrorHandler errorHandler;
    private final Set<String> subscriptions = Collections.synchronizedSet(new HashSet<String>());
    private volatile boolean loggedOut;

    private volatile Runnable hbStopper = new Runnable() {
        public void run() {
        }
    };

    public JetlangStreamSession(Object id, MessageStreamWriter socket, Fiber sendFiber, ErrorHandler errorHandler) {
        super(id);
        this.socket = socket;
        this.sendFiber = sendFiber;
        this.errorHandler = errorHandler;
    }

    public void startHeartbeat(int interval, TimeUnit unit) {
        if (interval > 0) {
            Runnable send = new Runnable() {
                public void run() {
                    write(MsgTypes.Heartbeat);
                }

                @Override
                public String toString() {
                    return "JetlangStreamSession.writeHeartbeat()";
                }
            };
            final Disposable disposeHb = sendFiber.scheduleWithFixedDelay(send, interval, interval, unit);
            hbStopper = new Runnable() {
                AtomicBoolean stopped = new AtomicBoolean(false);

                public void run() {
                    if (stopped.compareAndSet(false, true)) {
                        disposeHb.dispose();
                    }
                }
            };
        }
    }

    void onSubscriptionRequest(String topic) {
        subscriptions.add(topic);
        SubscriptionRequest.publish(new SessionTopic(topic, this));
    }

    public void onUnsubscribeRequest(String top) {
        subscriptions.remove(top);
        UnsubscribeRequest.publish(top);
    }

    public void write(final int byteToWrite) {
        Runnable r = new Runnable() {
            public void run() {
                try {
                    socket.writeByteAsInt(byteToWrite);
                } catch (IOException e) {
                    handleDisconnect(e);
                }
            }
        };
        sendFiber.execute(r);
    }

    private void handleDisconnect(IOException e) {
        socket.tryClose();
        if (!loggedOut) {
            errorHandler.onException(e);
        }
    }

    public boolean disconnect() {
        return socket.tryClose();
    }

    @Override
    public void afterLogout() {
        loggedOut = true;
        hbStopper.run();
    }

    @Override
    public <T> void publish(final String topic, final T msg) {
        Runnable r = new Runnable() {
            public void run() {
                try {
                    socket.write(topic, msg);
                } catch (IOException e) {
                    handleDisconnect(e);
                }
            }

            public String toString() {
                return "JetlangStreamSession.publish(" + topic + ", " + msg + ")";
            }
        };
        sendFiber.execute(r);
    }

    @Override
    public void publish(final byte[] data) {
        Runnable r = new Runnable() {
            public void run() {
                writeBytesOnSendFiberThread(data);
            }
        };
        sendFiber.execute(r);
    }

    private void writeBytesOnSendFiberThread(byte[] data) {
        try {
            socket.writeBytes(data);
        } catch (IOException e) {
            handleDisconnect(e);
        }
    }

    @Override
    public void reply(final int reqId, final String replyTopic, final Object replyMsg) {
        Runnable replyRunner = new Runnable() {
            public void run() {
                try {
                    socket.writeReply(reqId, replyTopic, replyMsg);
                } catch (IOException e) {
                    handleDisconnect(e);
                }
            }
        };
        sendFiber.execute(replyRunner);
    }


    @Override
    public void publishIfSubscribed(String topic, final byte[] data) {
        if (subscriptions.contains(topic)) {
            Runnable r = new Runnable() {
                public void run() {
                    writeBytesOnSendFiberThread(data);
                }
            };
            sendFiber.execute(r);
        }
    }
}
