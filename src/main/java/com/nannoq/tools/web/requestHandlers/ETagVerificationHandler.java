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
 * Created by anders on 02/08/16.
 */
public class ETagVerificationHandler implements Handler<RoutingContext> {
    private static final Logger logger = LoggerFactory.getLogger(ETagVerificationHandler.class.getSimpleName());

    private final String RSC_ID;
    private final Class TYPE;
    private final RedisClient REDIS_CLIENT;

    public ETagVerificationHandler(Vertx vertx, Class type, String resourceIdentifier, JsonObject appConfig) {
        this.TYPE = type;
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
