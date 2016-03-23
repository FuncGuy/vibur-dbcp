/**
 * Copyright 2013 Simeon Malchev
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.vibur.dbcp.cache;

import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Objects.requireNonNull;

/**
 * A thin wrapper around the raw JDBC {@code Statement} object which allows us to augment it with useful "state"
 * information. The instances of this class are used as a cached {@code value} (in {@code ConcurrentMap} cache
 * implementation) for the invocations of {@code Connection.prepareStatement} and {@code Connection.prepareCall}
 * methods, and their "state" is describing whether the {@code Statement} object is currently AVAILABLE, IN_USE,
 * or EVICTED.
 *
 * @see ConnMethod
 *
 * @author Simeon Malchev
 */
public class StatementHolder {

    /**
     * The 3 different states in which a StatementHolder instance can be, when it is used as a cached value:
     */
    public static final int AVAILABLE = 0;
    public static final int IN_USE = 1;
    public static final int EVICTED = 2;

    private final Statement value; // the underlying raw JDBC Statement
    private final AtomicInteger state; // a null value means that this StatementHolder instance is not included in the cache

    public StatementHolder(Statement value, AtomicInteger state) {
        this.value = requireNonNull(value);
        this.state = state;
    }

    public Statement value() {
        return value;
    }

    public AtomicInteger state() {
        return state;
    }
}