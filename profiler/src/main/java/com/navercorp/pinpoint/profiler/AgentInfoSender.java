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

package com.navercorp.pinpoint.profiler;

import com.navercorp.pinpoint.profiler.context.thrift.MessageConverter;
import com.navercorp.pinpoint.profiler.metadata.AgentInfo;
import com.navercorp.pinpoint.profiler.metadata.MetaDataType;
import com.navercorp.pinpoint.profiler.sender.EnhancedDataSender;
import com.navercorp.pinpoint.profiler.sender.ResultResponse;
import com.navercorp.pinpoint.profiler.util.AgentInfoFactory;
import com.navercorp.pinpoint.rpc.ResponseMessage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author emeroad
 * @author koo.taejin
 * @author HyunGil Jeong
 */
public class AgentInfoSender {
    // refresh daily
    private static final long DEFAULT_AGENT_INFO_REFRESH_INTERVAL_MS = 24 * 60 * 60 * 1000L;
    // retry every 3 seconds
    private static final long DEFAULT_AGENT_INFO_SEND_INTERVAL_MS = 3 * 1000L;
    // retry 3 times per attempt
    private static final int DEFAULT_MAX_TRY_COUNT_PER_ATTEMPT = 3;

    private final Logger logger = LogManager.getLogger(this.getClass());

    private final EnhancedDataSender<MetaDataType> dataSender;
    private final AgentInfoFactory agentInfoFactory;
    private final long refreshIntervalMs;
    private final long sendIntervalMs;
    private final int maxTryPerAttempt;
    private final Scheduler scheduler;
    private final MessageConverter<Object, ResultResponse> messageConverter;

    private AgentInfoSender(Builder builder) {
        this.dataSender = builder.dataSender;
        this.agentInfoFactory = builder.agentInfoFactory;
        this.refreshIntervalMs = builder.refreshIntervalMs;
        this.sendIntervalMs = builder.sendIntervalMs;
        this.maxTryPerAttempt = builder.maxTryPerAttempt;
        this.scheduler = new Scheduler();
        this.messageConverter = builder.messageConverter;
    }

    public void start() {
        scheduler.start();
    }

    public void stop() {
        scheduler.stop();
        logger.info("AgentInfoSender stopped");
    }

    public void refresh() {
        scheduler.refresh();
    }

    private interface SuccessListener {
        void onSuccess();

        SuccessListener NO_OP = new SuccessListener() {
            @Override
            public void onSuccess() {
                // noop
            }
        };
    }

    private class Scheduler {

        private static final long IMMEDIATE = 0L;
        private final Timer timer = new Timer("Pinpoint-AgentInfoSender-Timer", true);
        private final Object lock = new Object();
        // protected by lock's monitor
        private boolean isRunning = true;

        private Scheduler() {
            // preload
            AgentInfoSendTask task = new AgentInfoSendTask(SuccessListener.NO_OP);
            task.run();
        }

        public void start() {
            final SuccessListener successListener = new SuccessListener() {
                @Override
                public void onSuccess() {
                    schedule(this, maxTryPerAttempt, refreshIntervalMs, sendIntervalMs);
                }
            };
            if (logger.isDebugEnabled()) {
                logger.debug("Start scheduler of agentInfoSender");
            }
            schedule(successListener, Integer.MAX_VALUE, IMMEDIATE, sendIntervalMs);
        }

        public void refresh() {
            if (logger.isDebugEnabled()) {
                logger.debug("Refresh scheduler of agentInfoSender");
            }
            schedule(SuccessListener.NO_OP, maxTryPerAttempt, IMMEDIATE, sendIntervalMs);
        }

        private void schedule(SuccessListener successListener, int retryCount, long delay, long period) {
            synchronized (lock) {
                if (isRunning) {
                    AgentInfoSendTask task = new AgentInfoSendTask(successListener, retryCount);
                    timer.scheduleAtFixedRate(task, delay, period);
                }
            }
        }

        public void stop() {
            synchronized (lock) {
                isRunning = false;
                timer.cancel();
            }
        }
    }

    private class AgentInfoSendTask extends TimerTask {

        private final SuccessListener taskHandler;
        private final int retryCount;
        private final AtomicInteger counter;

        private AgentInfoSendTask(SuccessListener taskHandler) {
            this(taskHandler, 0);
        }

        private AgentInfoSendTask(SuccessListener taskHandler, int retryCount) {
            this.taskHandler = Objects.requireNonNull(taskHandler, "taskHandler");
            this.retryCount = retryCount;
            this.counter = new AtomicInteger(0);
        }

        @Override
        public void run() {
            int runCount = counter.incrementAndGet();
            if (runCount > retryCount) {
                this.cancel();
                return;
            }
            boolean isSuccessful = sendAgentInfo();
            if (isSuccessful) {
                logger.info("AgentInfo sent.");
                this.cancel();
                taskHandler.onSuccess();
            }
        }

        private boolean sendAgentInfo() {
            try {
                AgentInfo agentInfo = agentInfoFactory.createAgentInfo();

                logger.info("Sending AgentInfo {}", agentInfo);
                ResponseFutureListener<ResponseMessage, Throwable> listener = new ResponseFutureListener<>();
                dataSender.request(agentInfo, listener);
                ResponseMessage responseMessage = listener.getResponseFuture().get(3000, TimeUnit.MILLISECONDS);
                if (responseMessage == null) {
                    logger.warn("result not set");
                    return false;
                }
                final ResultResponse result = messageConverter.toMessage(responseMessage);
                if (!result.isSuccess()) {
                    logger.warn("request unsuccessful. Cause : {}", result.getMessage());
                }
                return result.isSuccess();
            } catch (ExecutionException ex) {
                logError(ex.getCause());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                logError(ex);
            } catch (TimeoutException ex) {
                logError(ex);
            }
            return false;
        }

        private void logError(Throwable cause) {
            logger.warn("failed to send agent info", cause);
        }
    }

    public static class Builder {
        private final EnhancedDataSender<MetaDataType> dataSender;
        private final AgentInfoFactory agentInfoFactory;
        private long refreshIntervalMs = DEFAULT_AGENT_INFO_REFRESH_INTERVAL_MS;
        private long sendIntervalMs = DEFAULT_AGENT_INFO_SEND_INTERVAL_MS;
        private int maxTryPerAttempt = DEFAULT_MAX_TRY_COUNT_PER_ATTEMPT;
        private MessageConverter<Object, ResultResponse> messageConverter;

        public Builder(EnhancedDataSender<MetaDataType> dataSender, AgentInfoFactory agentInfoFactory) {
            this.dataSender = Objects.requireNonNull(dataSender, "dataSender");
            this.agentInfoFactory = Objects.requireNonNull(agentInfoFactory, "agentInfoFactory");
        }

        public Builder refreshInterval(long refreshIntervalMs) {
            this.refreshIntervalMs = refreshIntervalMs;
            return this;
        }

        public Builder sendInterval(long sendIntervalMs) {
            this.sendIntervalMs = sendIntervalMs;
            return this;
        }

        public Builder maxTryPerAttempt(int maxTryCountPerAttempt) {
            this.maxTryPerAttempt = maxTryCountPerAttempt;
            return this;
        }

        public Builder setMessageConverter(MessageConverter<Object, ResultResponse> messageConverter) {
            this.messageConverter = messageConverter;
            return this;
        }

        public AgentInfoSender build() {
            if (this.refreshIntervalMs <= 0) {
                throw new IllegalStateException("agentInfoRefreshIntervalMs must be greater than 0");
            }
            if (this.sendIntervalMs <= 0) {
                throw new IllegalStateException("agentInfoSendIntervalMs must be greater than 0");
            }
            if (this.maxTryPerAttempt <= 0) {
                throw new IllegalStateException("maxTryPerAttempt must be greater than 0");
            }
            return new AgentInfoSender(this);
        }
    }
}