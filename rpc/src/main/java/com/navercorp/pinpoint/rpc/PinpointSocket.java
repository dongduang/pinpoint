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

package com.navercorp.pinpoint.rpc;

import com.navercorp.pinpoint.rpc.cluster.ClusterOption;
import com.navercorp.pinpoint.rpc.stream.ClientStreamChannel;
import com.navercorp.pinpoint.rpc.stream.ClientStreamChannelEventHandler;
import com.navercorp.pinpoint.rpc.stream.StreamException;

import java.net.SocketAddress;
import java.util.concurrent.CompletableFuture;

/**
 * @author Taejin Koo
 */
public interface PinpointSocket {

    void send(byte[] payload);

    CompletableFuture<ResponseMessage> request(byte[] payload);

    void response(int requestId, byte[] payload);

    ClientStreamChannel openStream(byte[] payload, ClientStreamChannelEventHandler streamChannelEventHandler)  throws StreamException;
    ClientStreamChannel openStreamAndAwait(byte[] payload, ClientStreamChannelEventHandler streamChannelEventHandler, long timeout)  throws StreamException;

    SocketAddress getRemoteAddress();

    void close();

    ClusterOption getLocalClusterOption();
    ClusterOption getRemoteClusterOption();

}
