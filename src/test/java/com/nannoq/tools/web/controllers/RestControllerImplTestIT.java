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
 */

package com.nannoq.tools.web.controllers;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsyncClient;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBTable;
import com.hazelcast.config.Config;
import com.nannoq.tools.repository.dynamodb.DynamoDBRepository;
import com.nannoq.tools.repository.repository.results.CreateResult;
import com.nannoq.tools.web.controllers.model.TestModel;
import com.nannoq.tools.web.controllers.repositories.TestModelRESTController;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.vertx.core.*;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.spi.cluster.hazelcast.HazelcastClusterManager;
import org.apache.http.HttpHeaders;
import org.junit.*;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import redis.embedded.RedisServer;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static com.nannoq.tools.web.RoutingHelper.routeWithBodyAndLogger;
import static com.nannoq.tools.web.RoutingHelper.routeWithLogger;
import static io.restassured.RestAssured.given;
import static java.util.stream.Collectors.toList;
import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertNull;

/**
 * @author Anders Mikkelsen
 * @version 17.11.2017
 */
@RunWith(VertxUnitRunner.class)
public class RestControllerImplTestIT {
    private static final Logger logger = LoggerFactory.getLogger(RestControllerImplTestIT.class.getSimpleName());

    private final JsonObject config = new JsonObject()
            .put("redis_host", System.getProperty("redis.endpoint"))
            .put("redis_port", Integer.parseInt(System.getProperty("redis.port")))
            .put("dynamo_endpoint", System.getProperty("dynamo.endpoint"))
            .put("dynamo_db_iam_id", "someTestId")
            .put("dynamo_db_iam_key", "someTestKey")
            .put("content_bucket", "someName");

    private final Date testDate = new Date();
    private final Supplier<TestModel> nonNullTestModel = () -> new TestModel()
            .setSomeStringOne("testString")
            .setSomeStringTwo(UUID.randomUUID().toString())
            .setSomeStringThree("testStringThree")
            .setSomeLong(1L)
            .setSomeDate(testDate)
            .setSomeDateTwo(new Date());

    private static Vertx vertx;
    private int port;
    private RedisServer redisServer;
    private DynamoDBRepository<TestModel> repo;
    private TestModelRESTController controller;
    private final String tableName = TestModel.class.getAnnotation(DynamoDBTable.class).tableName();
    private final Map<String, Class> testMap = Collections.singletonMap(tableName, TestModel.class);
    private Function<RoutingContext, JsonObject> idSupplier = r -> new JsonObject()
            .put("hash", r.request().getParam("hash"))
            .put("range", r.request().getParam("range"));

    @Rule
    public TestName name = new TestName();

    @SuppressWarnings("Duplicates")
    @BeforeClass
    public static void setUpClass(TestContext testContext) {
        Async async = testContext.async();

        Config hzConfig = new Config() ;
        hzConfig.setProperty( "hazelcast.logging.type", "log4j2" );
        HazelcastClusterManager mgr = new HazelcastClusterManager();
        mgr.setConfig(hzConfig);
        VertxOptions options = new VertxOptions().setClusterManager(mgr);

        Vertx.clusteredVertx(options, clustered -> {
            if (clustered.failed()) {
                System.out.println("Vertx not able to cluster!");

                System.exit(-1);
            } else {
                vertx = clustered.result();

                logger.info("Vertx is Running!");
            }

            async.complete();
        });
    }

    @Before
    public void setUp(TestContext testContext) throws Exception {
        logger.info("Running " + name.getMethodName());

        Async async = testContext.async();

        redisServer = new RedisServer(Integer.parseInt(System.getProperty("redis.port")));
        redisServer.start();

        DynamoDBRepository.initializeDynamoDb(config, testMap, res -> {
            if (res.failed()) {
                testContext.fail(res.cause());
            } else {
                try {
                    port = Integer.parseInt(System.getProperty("vertx.port"));
                    repo = new DynamoDBRepository<>(vertx, TestModel.class, config);
                    controller = new TestModelRESTController(vertx, config, repo, idSupplier);

                    RestAssured.port = port;
                    RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();

                    Async httpAsync = testContext.async();
                    vertx.createHttpServer()
                            .requestHandler(createRouter(controller)::accept)
                            .listen(port, listenRes -> httpAsync.complete());
                } catch (Exception e) {
                    testContext.fail(e);
                }
            }

            long redisChecker = System.currentTimeMillis();

            while (!redisServer.isActive()) {
                if (System.currentTimeMillis() > redisChecker + 10000) {
                    logger.error("No connection with Redis, terminating!");

                    System.exit(-1);
                } else {
                    logger.warn("Waiting for redis...");
                }
            }

            async.complete();
        });
    }

    private Router createRouter(TestModelRESTController controller) {
        Router router = Router.router(vertx);

        routeWithLogger(() -> router.get("/parent/:hash/testModels/:range"), route -> route.get().handler(controller::show));
        routeWithLogger(() -> router.get("/parent/:hash/testModels"), route -> route.get().handler(controller::index));
        routeWithBodyAndLogger(() -> router.post("/parent/:hash/testModels"), route -> route.get().handler(controller::create));
        routeWithBodyAndLogger(() -> router.put("/parent/:hash/testModels/:range"), route -> route.get().handler(controller::update));
        routeWithLogger(() -> router.delete("/parent/:hash/testModels/:range"), route -> route.get().handler(controller::destroy));

        return router;
    }

    @After
    public void tearDown() {
        final AmazonDynamoDBAsyncClient amazonDynamoDBAsyncClient = new AmazonDynamoDBAsyncClient();
        amazonDynamoDBAsyncClient.withEndpoint(config.getString("dynamo_endpoint"));
        amazonDynamoDBAsyncClient.deleteTable(tableName);

        repo = null;
        controller = null;
        redisServer.stop();
        redisServer = null;

        logger.info("Closing " + name.getMethodName());
    }

    @SuppressWarnings("Duplicates")
    private void createXItems(int count, Handler<AsyncResult<List<CreateResult<TestModel>>>> resultHandler) {
        final List<TestModel> items = new ArrayList<>();
        List<Future> futures = new CopyOnWriteArrayList<>();

        IntStream.range(0, count).forEach(i -> {
            TestModel testModel = (TestModel) nonNullTestModel.get().setRange(UUID.randomUUID().toString());

            LocalDate startDate = LocalDate.of(1990, 1, 1);
            LocalDate endDate = LocalDate.now();
            long start = startDate.toEpochDay();
            long end = endDate.toEpochDay();

            @SuppressWarnings("ConstantConditions")
            long randomEpochDay = ThreadLocalRandom.current().longs(start, end).findAny().getAsLong();

            testModel.setSomeDate(new Date(randomEpochDay + 1000L));
            testModel.setSomeDateTwo(new Date(randomEpochDay));

            items.add(testModel);
        });

        items.forEach(item -> {
            Future<CreateResult<TestModel>> future = Future.future();

            repo.create(item, future.completer());

            futures.add(future);

            try {
                Thread.sleep(10);
            } catch (InterruptedException ignored) {}
        });

        CompositeFuture.all(futures).setHandler(res -> {
            if (res.failed()) {
                resultHandler.handle(Future.failedFuture(res.cause()));
            } else {
                @SuppressWarnings("unchecked")
                final List<CreateResult<TestModel>> collect = futures.stream()
                        .map(Future::result)
                        .map(o -> (CreateResult<TestModel>) o)
                        .collect(toList());

                resultHandler.handle(Future.succeededFuture(collect));
            }
        });
    }

    @SuppressWarnings("Duplicates")
    @AfterClass
    public static void tearDownClass(TestContext testContext) {
        Async async = testContext.async();

        vertx.close(res -> {
            if (res.failed()) {
                logger.error("Vertx failed close!", res.cause());

                vertx.close();
            } else {
                logger.info("Vertx is closed!");
            }

            async.complete();
        });
    }

    @Test
    public void show() {
        Response response = createByRest(nonNullTestModel.get());
        TestModel testModel = Json.decodeValue(response.asString(), TestModel.class);
        response = getResponse(testModel, null, 200);
        response = getResponse(testModel, response.header(HttpHeaders.ETAG), 304);
    }

    private Response getResponse(TestModel testModel, String eTag, int statusCode) {
        return given().
                header(HttpHeaders.IF_NONE_MATCH, eTag != null ? eTag : "NoEtag").
            when().
                get("/parent/" + testModel.getHash() + "/testModels/" + testModel.getRange()).
            then().
                statusCode(statusCode).
                    extract().
                        response();
    }

    @Test
    public void index(TestContext testContext) {
        Async async = testContext.async();

        createXItems(25, createRes -> getPage(null, async));
    }

    private void getPage(String pageToken, Async async) {
        Response response = getIndex(null, pageToken, 200);
        String etag = response.header(HttpHeaders.ETAG);
        getIndex(etag, pageToken, 304);
        String newPageToken = response.jsonPath().getString("pageToken");

        logger.info("New Token is: " + newPageToken);

        if (newPageToken.equals("END_OF_LIST")) {
            async.complete();
        } else {
            getPage(newPageToken, async);
        }
    }

    private Response getIndex(String eTag, String pageToken, int statusCode) {
        return given().
                header(HttpHeaders.IF_NONE_MATCH, eTag != null ? eTag : "NoEtag").
            when().
                get("/parent/testString/testModels" + (pageToken != null ? "?pageToken=" + pageToken : "")).
            then().
                statusCode(statusCode).
                    extract().
                        response();
    }

    @Test
    public void create() {
        Response response = createByRest(nonNullTestModel.get());
        TestModel testModel = Json.decodeValue(response.asString(), TestModel.class);
        response = getResponse(testModel, null, 200);
        getResponse(testModel, response.header(HttpHeaders.ETAG), 304);
    }

    private Response createByRest(TestModel testModel) {
        return given().
                body(new JsonObject()
                        .put("someDateTwo", testModel.getSomeDateTwo().getTime())
                        .encode()).
            when().
                post("/parent/testString/testModels").
            then().
                statusCode(201).
                    extract().
                        response();
    }

    @Test
    public void update() {
        Response response = createByRest(nonNullTestModel.get());
        TestModel testModel = Json.decodeValue(response.asString(), TestModel.class);
        response = getResponse(testModel, null, 200);
        getResponse(testModel, response.header(HttpHeaders.ETAG), 304);

        testModel.setSomeLong(1L);

        response = given().
                body(testModel.toJsonFormat().encode()).
        when().
                put("/parent/" + testModel.getHash() + "/testModels/" + testModel.getRange()).
        then().
                statusCode(200).
                    extract().
                        response();

        TestModel updatedTestModel = Json.decodeValue(response.asString(), TestModel.class);
        testModel = Json.decodeValue(getResponse(testModel, null, 200).asString(), TestModel.class);

        assertEquals(new Long(1L), testModel.getSomeLong());
        assertEquals(new Long(1L), updatedTestModel.getSomeLong());
    }

    @Test
    public void delete() {
        Response response = createByRest(nonNullTestModel.get());
        TestModel testModel = Json.decodeValue(response.asString(), TestModel.class);
        response = getResponse(testModel, null, 200);
        getResponse(testModel, response.header(HttpHeaders.ETAG), 304);
        destroyByRest(testModel);
        getResponse(testModel, null, 404);
    }

    @SuppressWarnings("UnusedReturnValue")
    private Response destroyByRest(TestModel testModel) {
        return given().
            when().
                delete("/parent/" + testModel.getHash() + "/testModels/" + testModel.getRange()).
            then().
                statusCode(204).
                    extract().
                        response();
    }
}