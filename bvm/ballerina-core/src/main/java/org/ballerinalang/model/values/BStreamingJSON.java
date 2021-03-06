/*
*  Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
*  WSO2 Inc. licenses this file to you under the Apache License,
*  Version 2.0 (the "License"); you may not use this file except
*  in compliance with the License.
*  You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
*  Unless required by applicable law or agreed to in writing,
*  software distributed under the License is distributed on an
*  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
*  KIND, either express or implied.  See the License for the
*  specific language governing permissions and limitations
*  under the License.
*/
package org.ballerinalang.model.values;

import org.ballerinalang.model.JSONDataSource;
import org.ballerinalang.model.types.BArrayType;
import org.ballerinalang.model.types.BTypes;
import org.ballerinalang.model.util.JsonGenerator;
import org.ballerinalang.util.exceptions.BallerinaException;

import java.io.IOException;
import java.io.OutputStream;

/**
 * {@link BStreamingJSON} represent a JSON array generated from a {@link JSONDataSource}.
 * 
 * @since 0.981.0
 */
public class BStreamingJSON extends BRefValueArray {

    JSONDataSource datasource;

    public BStreamingJSON(JSONDataSource datasource) {
        this.datasource = datasource;
        this.values = (BRefType[]) newArrayInstance(BRefType.class);
        this.arrayType = new BArrayType(BTypes.typeJSON);
    }

    @Override
    public void add(long index, BRefType<?> value) {
        // If the the index is larger than the size, and data-source has more content,
        // then read data from data-source until the index, or until the end of the data-source.
        while (index >= size && datasource.hasNext()) {
            appendToCache(datasource.next());
        }

        super.add(index, value);
    }

    @Override
    public void append(BRefType<?> value) {
        if (datasource.hasNext()) {
            buildDatasource();
        }

        super.append(value);
    }

    @Override
    public BRefType<?> get(long index) {
        // If the the index is larger than the size, and datasource has more content,
        // then read data from data-source until the index, or until the end of the data-source.
        while (index >= size && datasource.hasNext()) {
            appendToCache(datasource.next());
        }

        return super.get(index);
    }

    @Override
    public void serialize(OutputStream outputStream) {
        /*
         * Below order is important, where if the value is generated from a streaming data source,
         * it should be able to serialize the data out again using the value
         */
        try {
            JsonGenerator gen = new JsonGenerator(outputStream);
            gen.writeStartArray();

            // First serialize the values loaded to memory
            for (int i = 0; i < size; i++) {
                gen.serialize(values[i]);
            }

            // Then serialize remaining data in the data-source
            while (datasource.hasNext()) {
                gen.serialize(datasource.next());
            }
            gen.writeEndArray();
            gen.flush();
        } catch (IOException e) {
            throw new BallerinaException("error occurred while serializing data", e);
        }
    }

    @Override
    public BRefType<?>[] getValues() {
        if (datasource.hasNext()) {
            buildDatasource();
        }
        return values;
    }

    @Override
    public String stringValue() {
        if (datasource.hasNext()) {
            buildDatasource();
        }

        return super.stringValue();
    }

    @Override
    public BIterator newIterator() {
        return new BStreamingJSONIterator(this);
    }

    void appendToCache(BRefType<?> value) {
        super.add(size, value);
    }

    private void buildDatasource() {
        try {
            while (datasource.hasNext()) {
                appendToCache(datasource.next());
            }
        } catch (Throwable t) {
            throw new BallerinaException("error occurred while building JSON: ", t);
        }
    }

    /**
     * {@code {@link BStreamingJSONIterator}} provides iterator implementation for Ballerina array values.
     *
     * @since 0.982.0
     */
    static class BStreamingJSONIterator implements BIterator {
        BStreamingJSON array;
        long cursor = 0;

        BStreamingJSONIterator(BStreamingJSON value) {
            this.array = value;
        }

        @Override
        public BValue[] getNext(int arity) {
            BValue[] values;
            // If the current index is loaded in to memory, then read from it
            if (cursor < array.size) {
                if (arity == 1) {
                    values = new BValue[] { array.getBValue(cursor) };
                } else {
                    values = new BValue[] { new BInteger(cursor), array.getBValue(cursor) };
                }
            } else {
                // Otherwise read the next value from data-source and cache it in memory
                BRefType<?> nextVal = array.datasource.next();
                array.appendToCache(nextVal);
                values = new BValue[] { nextVal };
            }

            this.cursor++;
            return values;
        }

        @Override
        public boolean hasNext() {
            if (cursor < array.size) {
                return true;
            }

            return array.datasource.hasNext();
        }
    }
}
