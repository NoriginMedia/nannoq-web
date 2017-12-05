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

package com.nannoq.tools.web.controllers.model;

import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBDocument;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBVersionAttribute;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.nannoq.tools.repository.models.ETagable;
import java.util.Objects;

/**
 * @author Anders Mikkelsen
 * @version 17.11.2017
 */
@DynamoDBDocument
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TestDocument implements ETagable {
    private String etag;
    private String someStringOne;
    private String someStringTwo;
    private String someStringThree;
    private String someStringFour;
    private Long version;

    public TestDocument() {

    }

    public String getSomeStringOne() {
        return someStringOne;
    }

    public void setSomeStringOne(String someStringOne) {
        this.someStringOne = someStringOne;
    }

    public String getSomeStringTwo() {
        return someStringTwo;
    }

    public void setSomeStringTwo(String someStringTwo) {
        this.someStringTwo = someStringTwo;
    }

    public String getSomeStringThree() {
        return someStringThree;
    }

    public void setSomeStringThree(String someStringThree) {
        this.someStringThree = someStringThree;
    }

    public String getSomeStringFour() {
        return someStringFour;
    }

    public void setSomeStringFour(String someStringFour) {
        this.someStringFour = someStringFour;
    }

    @DynamoDBVersionAttribute
    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    @Override
    public String getEtag() {
        return etag;
    }

    @Override
    public TestDocument setEtag(String etag) {
        this.etag = etag;

        return this;
    }

    @Override
    public String generateEtagKeyIdentifier() {
        return getSomeStringOne() != null ? "data_api_testDocument_etag_" + getSomeStringOne() : "NoDocumentTag";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TestDocument that = (TestDocument) o;

        return Objects.equals(getSomeStringOne(), that.getSomeStringOne()) &&
                Objects.equals(getSomeStringTwo(), that.getSomeStringTwo()) &&
                Objects.equals(getSomeStringThree(), that.getSomeStringThree()) &&
                Objects.equals(getSomeStringFour(), that.getSomeStringFour()) &&
                Objects.equals(getVersion(), that.getVersion());
    }

    @Override
    public int hashCode() {
        return Objects.hash(someStringOne, someStringTwo, someStringThree, someStringFour, version);
    }
}
