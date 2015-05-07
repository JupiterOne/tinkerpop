/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.tinkerpop.gremlin.driver;

import org.apache.tinkerpop.gremlin.driver.exception.ResponseException;
import org.apache.tinkerpop.gremlin.driver.message.ResponseMessage;
import org.apache.tinkerpop.gremlin.driver.message.ResponseStatusCode;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.ReferenceCountUtil;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;

/**
 * Holder for internal handler classes used in constructing the channel pipeline.
 *
 * @author Stephen Mallette (http://stephen.genoprime.com)
 */
class Handler {

    /**
     * Takes a map of requests pending responses and writes responses to the {@link ResultQueue} of a request
     * as the {@link ResponseMessage} objects are deserialized.
     */
    static class GremlinResponseHandler extends SimpleChannelInboundHandler<ResponseMessage> {
        private final ConcurrentMap<UUID, ResultQueue> pending;

        public GremlinResponseHandler(final ConcurrentMap<UUID, ResultQueue> pending) {
            this.pending = pending;
        }

        @Override
        protected void channelRead0(final ChannelHandlerContext channelHandlerContext, final ResponseMessage response) throws Exception {
            try {
                if (response.getStatus().getCode() == ResponseStatusCode.SUCCESS ||
                        response.getStatus().getCode() == ResponseStatusCode.PARTIAL_CONTENT) {
                    final Object data = response.getResult().getData();
                    if (data instanceof List) {
                        // unrolls the collection into individual results to be handled by the queue.
                        final List<Object> listToUnroll = (List<Object>) data;
                        final ResultQueue queue = pending.get(response.getRequestId());
                        listToUnroll.forEach(item -> queue.add(new Result(item)));
                    } else {
                        // since this is not a list it can just be added to the queue
                        pending.get(response.getRequestId()).add(new Result(response.getResult().getData()));
                    }
                } else {
                    pending.get(response.getRequestId()).markError(new ResponseException(response.getStatus().getCode(), response.getStatus().getMessage()));
                }

                // todo: should this go in finally? where is the catch?
                // as this is a non-PARTIAL_CONTENT code - the stream is done
                if (response.getStatus().getCode() != ResponseStatusCode.PARTIAL_CONTENT)
                    pending.remove(response.getRequestId()).markComplete();
            } finally {
                ReferenceCountUtil.release(response);
            }
        }
    }

}
