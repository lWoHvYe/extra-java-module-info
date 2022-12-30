/*
 *    Copyright (c) 2022.  lWoHvYe(Hongyan Wang)
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package org.gradlex.javamodule.moduleinfo;

import org.gradle.internal.impldep.org.eclipse.jgit.annotations.NonNull;
import org.gradle.internal.impldep.org.eclipse.jgit.annotations.Nullable;

import java.io.Serial;
import java.io.Serializable;
import java.util.*;

public class Tuple2<K, V> implements Iterable<Object>, Serializable {
    @Serial
    private static final long serialVersionUID = -3518082018884860684L;
    @NonNull
    final K k;
    @Nullable
    final V v;

    Tuple2(K k, V v) {
        this.k = Objects.requireNonNull(k, "k");
        this.v = v;
    }

    static <K, V> Tuple2<K, V> of(K k, V v) {
        return new Tuple2<>(k, v);
    }

    public List<Object> toList() {
        return Arrays.asList(this.toArray());
    }

    public Object[] toArray() {
        return new Object[]{this.k, this.v};
    }

    public Iterator<Object> iterator() {
        return Collections.unmodifiableList(this.toList()).iterator();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Tuple2<?, ?> tuple2)) return false;
        return k.equals(tuple2.k);
    }

    @Override
    public int hashCode() {
        return Objects.hash(k, v);
    }

    @Override
    public String toString() {
        return "Tuple2{" +
                "k=" + k +
                ", v=" + v +
                '}';
    }
}
