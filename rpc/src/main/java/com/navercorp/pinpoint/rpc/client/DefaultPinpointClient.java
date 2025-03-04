/*
 * Copyright 2018 NAVER Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.navercorp.pinpoint.rpc.client;

import com.navercorp.pinpoint.rpc.PinpointSocketException;
import com.navercorp.pinpoint.rpc.ResponseMessage;
import com.navercorp.pinpoint.rpc.cluster.ClusterOption;
import com.navercorp.pinpoint.rpc.stream.ClientStreamChannel;
import com.navercorp.pinpoint.rpc.stream.ClientStreamChannelEventHandler;
import com.navercorp.pinpoint.rpc.stream.StreamException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.SocketAddress;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * @author Woonduk Kang(emeroad)
 */
public class DefaultPinpointClient implements PinpointClient {
    private final Logger logger = LogManager.getLogger(this.getClass());

    private volatile PinpointClientHandler pinpointClientHandler;

    private volatile boolean closed;

    private final List<Consumer<PinpointClient>> reconnectEventListeners = new CopyOnWriteArrayList<>();

     public DefaultPinpointClient(PinpointClientHandler pinpointClientHandler) {
        this.pinpointClientHandler = Objects.requireNonNull(pinpointClientHandler, "pinpointClientHandler");
        pinpointClientHandler.setPinpointClient(this);
    }

    @Override
    public void reconnectSocketHandler(PinpointClientHandler pinpointClientHandler) {
        Objects.requireNonNull(pinpointClientHandler, "pinpointClientHandler");

        if (closed) {
            logger.warn("reconnectClientHandler(). pinpointClientHandler force close.");
            pinpointClientHandler.close();
            return;
        }
        logger.warn("reconnectClientHandler:{}", pinpointClientHandler);

        this.pinpointClientHandler = pinpointClientHandler;

        notifyReconnectEvent();
    }


    /*
        because reconnectEventListener's constructor contains Dummy and can't be access through setter,
        guarantee it is not null.
    */
    @Override
    public boolean addPinpointClientReconnectEventListener(Consumer<PinpointClient> eventListener) {
        if (eventListener == null) {
            return false;
        }

        return this.reconnectEventListeners.add(eventListener);
    }

    @Override
    public boolean removePinpointClientReconnectEventListener(Consumer<PinpointClient> eventListener) {
        if (eventListener == null) {
            return false;
        }

        return this.reconnectEventListeners.remove(eventListener);
    }

    private void notifyReconnectEvent() {
        for (Consumer<PinpointClient> eachListener : this.reconnectEventListeners) {
            eachListener.accept(this);
        }
    }

    @Override
    public void sendSync(byte[] bytes) {
        ensureOpen();
        pinpointClientHandler.sendSync(bytes);
    }

    @Override
    public CompletableFuture<Void> sendAsync(byte[] bytes) {
        ensureOpen();
        return pinpointClientHandler.sendAsync(bytes);
    }

    @Override
    public void send(byte[] bytes) {
        ensureOpen();
        pinpointClientHandler.send(bytes);
    }

    @Override
    public CompletableFuture<ResponseMessage> request(byte[] bytes) {
        if (pinpointClientHandler == null) {
            return returnFailureFuture();
        }
        return pinpointClientHandler.request(bytes);
    }


    @Override
    public void response(int requestId, byte[] payload) {
        ensureOpen();
        pinpointClientHandler.response(requestId, payload);
    }

    @Override
    public ClientStreamChannel openStream(byte[] payload, ClientStreamChannelEventHandler streamChannelEventHandler) throws StreamException {
        // StreamChannel must be changed into interface in order to throw the StreamChannel that returns failure.
        // fow now throw just exception
        ensureOpen();
        return pinpointClientHandler.openStream(payload, streamChannelEventHandler);
    }

    @Override
    public ClientStreamChannel openStreamAndAwait(byte[] payload, ClientStreamChannelEventHandler streamChannelEventHandler, long timeout) throws StreamException {
        // StreamChannel must be changed into interface in order to throw the StreamChannel that returns failure.
        // fow now throw just exception
        ensureOpen();
        return pinpointClientHandler.openStreamAndAwait(payload, streamChannelEventHandler, timeout);
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return pinpointClientHandler.getRemoteAddress();
    }

    @Override
    public ClusterOption getLocalClusterOption() {
        return pinpointClientHandler.getLocalClusterOption();
    }

    @Override
    public ClusterOption getRemoteClusterOption() {
        return pinpointClientHandler.getRemoteClusterOption();
    }

    private CompletableFuture<ResponseMessage> returnFailureFuture() {
        CompletableFuture<ResponseMessage> future = new CompletableFuture<>();
        future.completeExceptionally(new PinpointSocketException("pinpointClientHandler is null"));
        return future;
    }

    private void ensureOpen() {
        if (pinpointClientHandler == null) {
            throw new PinpointSocketException("pinpointClientHandler is null");
        }
    }

    /**
     * write ping packet on tcp channel
     * PinpointSocketException throws when writing fails.
     *
     */
    @Override
    public void sendPing() {
        PinpointClientHandler pinpointClientHandler = this.pinpointClientHandler;
        if (pinpointClientHandler == null) {
            return;
        }
        pinpointClientHandler.sendPing();
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
        }
        PinpointClientHandler pinpointClientHandler = this.pinpointClientHandler;
        if (pinpointClientHandler == null) {
            return;
        }
        pinpointClientHandler.close();
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public boolean isConnected() {
        return this.pinpointClientHandler.isConnected();
    }
}
