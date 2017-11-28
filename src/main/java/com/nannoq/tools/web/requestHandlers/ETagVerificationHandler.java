/*
 * MIT License
 *
 * Copyright (c) 2017 Anders Mikkelsen
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 */

package com.nannoq.tools.web.requestHandlers;

import com.nannoq.tools.repository.repository.RedisUtils;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.RoutingContext;
import io.vertx.redis.RedisClient;

import java.util.function.Consumer;

import static com.nannoq.tools.web.requestHandlers.RequestLogHandler.addLogMessageToRequestLog;

/**
 * This class defines the EtagVerificationHandler which is used to return all etaged requests on a hit.
 *
 * @author Anders Mikkelsen
 * @version 17.11.2017
 */
public class ETagVerificationHandler implements Handler<RoutingContext> {
    private static final Logger logger = LoggerFactory.getLogger(ETagVerificationHandler.class.getSimpleName());

    private final String RSC_ID;
    private final RedisClient REDIS_CLIENT;

    public ETagVerificationHandler(Vertx vertx, String resourceIdentifier, JsonObject appConfig) {
        this.RSC_ID = resourceIdentifier;
        this.REDIS_CLIENT = RedisUtils.getRedisClient(vertx, appConfig);
    }

    @Override
    public void handle(RoutingContext routingContext) {
        HttpServerRequest request = routingContext.request();
        String etag = request.getHeader("If-None-Match");

        if (etag == null) {
            routingContext.next();
        } else {
            String uri = request.absoluteURI();
            String id = uri.substring(uri.lastIndexOf("/") + 1);

            if (id.equals(RSC_ID)) {
                routingContext.next();
            } else {
                String rscId = !id.equals(RSC_ID) ? RSC_ID.replaceFirst(".$", "") : RSC_ID + "_s";
                String etagKey = "data_api_" + rscId + "_etag" + (id.equals(RSC_ID) ? "" : "_" + id);

                checkEtag(routingContext, etagKey, etag);
            }
        }
    }

    private void checkEtag(RoutingContext routingContext, String etagKey, String etag) {
        if (etagKey == null) {
            addLogMessageToRequestLog(routingContext, "EtagKey is null!");

            routingContext.next();
        } else {
            Consumer<RedisClient> consumer = redisClient -> redisClient.get(etagKey, result -> {
                if (result.failed()) {
                    addLogMessageToRequestLog(routingContext, "ETAG ERROR: Unable to get etag for etag verification...");

                    routingContext.next();
                } else {
                    String storedEtag = result.result();

                    if (logger.isInfoEnabled()) {
                        addLogMessageToRequestLog(routingContext,
                                "ETAG INFO: Stored etag: " + etagKey + " : " + storedEtag + " vs. " + etag);
                    }

                    if (storedEtag != null && etag.equals(storedEtag)) {
                        routingContext.fail(304);
                    } else {
                        routingContext.next();
                    }
                }
            });

            RedisUtils.performJedisWithRetry(REDIS_CLIENT, consumer);
        }
    }
}
