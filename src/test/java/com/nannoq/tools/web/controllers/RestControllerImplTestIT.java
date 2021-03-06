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

import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsyncClient;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBTable;
import com.hazelcast.config.Config;
import com.nannoq.tools.repository.dynamodb.DynamoDBRepository;
import com.nannoq.tools.repository.repository.results.CreateResult;
import com.nannoq.tools.web.controllers.model.TestModel;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.vertx.core.*;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.RunTestOnContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.ext.web.Router;
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
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static com.nannoq.tools.web.RoutingHelper.routeWithBodyAndLogger;
import static com.nannoq.tools.web.RoutingHelper.routeWithLogger;
import static io.restassured.RestAssured.given;
import static java.util.stream.Collectors.toList;
import static junit.framework.TestCase.assertEquals;
import static org.junit.Assert.assertNotEquals;

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
    private RestControllerImpl<TestModel> controller;
    private CrossModelAggregationController crossModelcontroller;
    private final String tableName = TestModel.class.getAnnotation(DynamoDBTable.class).tableName();
    private final Map<String, Class> testMap = Collections.singletonMap(tableName, TestModel.class);

    @Rule
    public TestName name = new TestName();
    
    @Rule
    public RunTestOnContext rule = new RunTestOnContext(new VertxOptions()
            .setMaxEventLoopExecuteTime(Long.MAX_VALUE)
            .setMaxWorkerExecuteTime(Long.MAX_VALUE));

    @SuppressWarnings("Duplicates")
    @BeforeClass
    public static void setUpClass(TestContext testContext) {
        Async async = testContext.async();

        Config hzConfig = new Config() ;
        hzConfig.setProperty("hazelcast.logging.type", "log4j2");
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
                    controller = new RestControllerImpl<>(vertx, TestModel.class, config, repo);
                    crossModelcontroller = new CrossModelAggregationController(k -> repo, new Class[]{TestModel.class});

                    RestAssured.port = port;
                    RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();

                    Async httpAsync = testContext.async();
                    vertx.createHttpServer()
                            .requestHandler(createRouter(controller, crossModelcontroller)::accept)
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

    private Router createRouter(RestControllerImpl<TestModel> controller,
                                CrossModelAggregationController crossModelcontroller) {
        Router router = Router.router(vertx);

        routeWithLogger(() -> router.get("/parent/:hash/testModels/:range"), route -> route.get().handler(controller::show));
        routeWithLogger(() -> router.get("/parent/:hash/testModels"), route -> route.get().handler(controller::index));
        routeWithBodyAndLogger(() -> router.post("/parent/:hash/testModels"), route -> route.get().handler(controller::create));
        routeWithBodyAndLogger(() -> router.put("/parent/:hash/testModels/:range"), route -> route.get().handler(controller::update));
        routeWithLogger(() -> router.delete("/parent/:hash/testModels/:range"), route -> route.get().handler(controller::destroy));

        routeWithLogger(() -> router.get("/aggregations"), route -> route.get().handler(crossModelcontroller));

        return router;
    }

    @After
    public void tearDown() {
        final AmazonDynamoDBAsync amazonDynamoDBAsyncClient = AmazonDynamoDBAsyncClient.asyncBuilder()
                .withEndpointConfiguration(new EndpointConfiguration(config.getString("dynamo_endpoint"), "eu-west-1"))
                .build();
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
            TestModel testModel = nonNullTestModel.get().setRange(UUID.randomUUID().toString());

            LocalDate startDate = LocalDate.of(1990, 1, 1);
            LocalDate endDate = LocalDate.now();
            long start = startDate.toEpochDay();
            long end = endDate.toEpochDay();

            @SuppressWarnings("ConstantConditions")
            long randomEpochDay = ThreadLocalRandom.current().longs(start, end).findAny().getAsLong();

            testModel.setSomeDate(new Date(randomEpochDay + 1000L));
            testModel.setSomeDateTwo(new Date(randomEpochDay));
            testModel.setSomeLong(new Random().nextLong());

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

        createXItems(25, createRes -> rule.vertx().executeBlocking(fut -> {
            try {
                Response response = getIndex(null, null, 200);
                getIndex(response.header(HttpHeaders.ETAG), null, 304);
                getIndex(response.header(HttpHeaders.ETAG), null, 200, "?limit=50");
                getPage(null, fut);
            } catch (Exception e) {
                testContext.fail(e);
            }
        }, false, res -> {
            if (res.failed()) {
                testContext.fail(res.cause());
            } else {
                async.complete();
            }
        }));
    }

    private void getPage(String pageToken, Future<Object> async) {
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
        return getIndex(eTag, pageToken, statusCode, null);
    }

    private Response getIndex(String eTag, String pageToken, int statusCode, String query) {
        String url = "/parent/testString/testModels" + (query != null ? query : "") +
                (pageToken != null ? (query != null ? "&" : "?") + "pageToken=" + pageToken : "");

        return given().
                urlEncodingEnabled(false).
                header(HttpHeaders.IF_NONE_MATCH, eTag != null ? eTag : "NoEtag").
            when().
                get(url).
            then().
                statusCode(statusCode).
                    extract().
                        response();
    }

    @Test
    public void aggregateIndex(TestContext testContext) {
        Async async = testContext.async();

        createXItems(100, createRes -> rule.vertx().executeBlocking(fut -> {
            try {
                String query = "?aggregate=%7B%22function%22%3A%22MAX%22%2C%22field%22%3A%22someLong%22%2C%22groupBy%22%3A%5B%7B%22groupBy%22%3A%22someStringOne%22%7D%5D%7D";
                final Response index = getIndex(null, null, 200, query);
                String etag = index.header(HttpHeaders.ETAG);
                testContext.assertEquals(etag, getIndex(null, null, 200, query).header(HttpHeaders.ETAG));
                getIndex(etag, null, 304, query);

                async.complete();
            } catch (Exception e) {
                testContext.fail(e);
            }
        }, false, res -> {
            if (res.failed()) {
                testContext.fail(res.cause());
            } else {
                async.complete();
            }
        }));
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
        String oldEtag = testModel.getEtag();
        response = getResponse(testModel, null, 200);
        assertEquals(oldEtag, response.header(HttpHeaders.ETAG));
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
        response = getResponse(testModel, null, 200);
        testModel = Json.decodeValue(response.asString(), TestModel.class);
        assertNotEquals(oldEtag, response.header(HttpHeaders.ETAG));

        assertEquals(new Long(1L), testModel.getSomeLong());
        assertEquals(new Long(1L), updatedTestModel.getSomeLong());
    }

    @Test
    public void delete() {
        Response response = createByRest(nonNullTestModel.get());
        TestModel testModel = Json.decodeValue(response.asString(), TestModel.class);
        response = getResponse(testModel, null, 200);
        getResponse(testModel, response.header(HttpHeaders.ETAG), 304);
        response = getIndex(null, null, 200);
        String etagRoot = response.header(HttpHeaders.ETAG);
        getIndex(response.header(HttpHeaders.ETAG), null, 304);
        String etagQuery = getIndex(response.header(HttpHeaders.ETAG), null, 200, "?limit=50").header(HttpHeaders.ETAG);
        destroyByRest(testModel);
        getResponse(testModel, testModel.getEtag(), 404);
        getResponse(testModel, null, 404);
        getIndex(etagRoot, null, 304);
        getIndex(etagQuery, null, 304, "?limit=50");
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
