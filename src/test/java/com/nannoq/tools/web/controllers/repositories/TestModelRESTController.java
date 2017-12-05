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

package com.nannoq.tools.web.controllers.repositories;

import com.nannoq.tools.repository.repository.Repository;
import com.nannoq.tools.web.controllers.RestControllerImpl;
import com.nannoq.tools.web.controllers.model.TestModel;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;

import java.util.UUID;
import java.util.function.Function;

/**
 * @author Anders Mikkelsen
 * @version 17.11.2017
 */
public class TestModelRESTController extends RestControllerImpl<TestModel> {
    public TestModelRESTController(Vertx vertx, JsonObject appConfig, Repository<TestModel> repository,
                                   Function<RoutingContext, JsonObject> idSupplier) {
        super(vertx, TestModel.class, appConfig, repository, idSupplier);
    }

    @Override
    public void postVerifyNotExists(TestModel newRecord, RoutingContext routingContext) {
        final JsonObject id = getAndVerifyId(routingContext);

        newRecord.setSomeStringOne(id.getString("hash"));
        newRecord.setSomeStringTwo(UUID.randomUUID().toString());

        super.postVerifyNotExists(newRecord, routingContext);
    }
}