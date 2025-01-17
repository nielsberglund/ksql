/*
 * Copyright 2018 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.ksql.execution.expression.tree;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.Iterables.isEmpty;
import static java.util.Objects.requireNonNull;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.errorprone.annotations.Immutable;
import java.util.List;
import java.util.Optional;

@Immutable
public final class QualifiedName {

  private final ImmutableList<String> parts;

  public static QualifiedName of(final String first, final String... rest) {
    requireNonNull(first, "first is null");
    return of(ImmutableList.copyOf(Lists.asList(first, rest)));
  }

  public static QualifiedName of(final String name) {
    requireNonNull(name, "name is null");
    return of(ImmutableList.of(name));
  }

  public static QualifiedName of(final Iterable<String> parts) {
    requireNonNull(parts, "parts is null");
    checkArgument(!isEmpty(parts), "parts is empty");
    return new QualifiedName(ImmutableList.copyOf(parts));
  }

  private QualifiedName(final ImmutableList<String> parts) {
    this.parts = requireNonNull(parts, "parts");
  }

  public List<String> getParts() {
    return parts;
  }

  @Override
  public String toString() {
    return Joiner.on('.').join(parts);
  }

  /**
   * For an identifier of the form "a.b.c.d", returns "a.b.c"
   * For an identifier of the form "a", returns absent
   */
  public Optional<QualifiedName> getPrefix() {
    if (parts.size() == 1) {
      return Optional.empty();
    }

    final ImmutableList<String> subList = parts.subList(0, parts.size() - 1);
    return Optional.of(new QualifiedName(subList));
  }

  public String getSuffix() {
    return Iterables.getLast(parts);
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    return parts.equals(((QualifiedName) o).parts);
  }

  @Override
  public int hashCode() {
    return parts.hashCode();
  }
}
