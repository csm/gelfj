package org.graylog2;

import java.io.IOException;
import java.io.OutputStream;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.security.Security;
import java.util.Comparator;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.google.common.collect.MinMaxPriorityQueue;

public class GelfTCPSender implements GelfSender {
	private volatile boolean shutdown = false;
	private String host;
	private InetAddress[] hosts;
	private int hostIndex;
	private int port;
	private SocketChannel channel;
	private long lastLookupTime;
	private int errorMessages = 5;
    private final MinMaxPriorityQueue<GelfMessage> messageQueue;
    private final Lock queueLock;
    private final Condition queueCondition;
    private Thread consumerThread;

	public GelfTCPSender() {
        messageQueue = MinMaxPriorityQueue
                .orderedBy(new Comparator<GelfMessage>() {
                    @Override
                    public int compare(GelfMessage o1, GelfMessage o2) {
                        return o1.compareTo(o2);
                    }
                }).maximumSize(512).create();
        queueLock = new ReentrantLock();
        queueCondition = queueLock.newCondition();
	}

	public GelfTCPSender(String host, int port) throws IOException {
        this();
		this.host = host;
		this.port = port;
		lookup();
		connect();
        consumerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!shutdown) {
                    GelfMessage msg = null;
                    queueLock.lock();
                    try {
                        if (messageQueue.isEmpty())
                            queueCondition.await(1, TimeUnit.MINUTES);
                        msg = messageQueue.poll();
                    }
                    catch (InterruptedException e) {
                        // pass
                    }
                    finally {
                        queueLock.unlock();
                    }
                    if (msg != null)
                        sendMessageWithRetry(msg, true);
                }
            }
        }, "GelfTCPSenderConsumer");
        consumerThread.setDaemon(true);
        consumerThread.start();
	}

	public boolean sendMessage(GelfMessage message) {
        queueLock.lock();
        try {
            boolean result = messageQueue.offer(message);
            if (result)
                queueCondition.signal();
            return result;
        } finally {
            queueLock.unlock();
        }
	}

	private boolean sendMessageWithRetry(GelfMessage message, boolean retry) {
		if (shutdown || !message.isValid()) {
			if (errorMessages > 0) {
				System.err.printf("not sending message, shutdown:%s valid?:%s message:%s", shutdown, message.isValid(), message);
				errorMessages--;
			}
			return false;
		}

		try {
			// Look up the DNS name again.
			if (System.currentTimeMillis() - lastLookupTime > TimeUnit.MILLISECONDS.convert(60, TimeUnit.SECONDS)) {
				lookup();
			}

			// reconnect if necessary
			if (channel == null || !channel.isConnected()) {
                if (channel != null) {
                    try {
                        channel.close();
                    } catch (IOException ioe) {
                        // ignore
                    }
                }
				connect();
			}

			ByteBuffer messageBuffer = message.toTCPBuffer();
			int remaining = messageBuffer.remaining();
			int written = channel.write(message.toTCPBuffer());
			if (written != remaining) {
				throw new IOException(String.format("short write, expected to write %d, wrote %d", remaining, written));
			}

			return true;
		} catch (IOException e) {
			// if an error occurs, signal failure
			if (errorMessages > 0) {
				System.err.printf("failed sending message");
				e.printStackTrace();
				errorMessages++;
			}
			try {
				channel.close();
			} catch (IOException e1) {
				// ignore
			}
			channel = null;
			if (retry) {
				return sendMessageWithRetry(message, false);
			}
			return false;
		}
	}

	private void connect() throws IOException {
		this.channel = SocketChannel.open(new InetSocketAddress(hosts[hostIndex], port));
		hostIndex = (hostIndex + 1) % hosts.length;
	}

	private void lookup() throws UnknownHostException {
		this.hosts = InetAddress.getAllByName(this.host);
		this.lastLookupTime = System.currentTimeMillis();
		hostIndex = 0;
	}

	public void close() {
		shutdown = true;
		try {
			if (channel != null) {
				channel.close();
				channel = null;
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}
