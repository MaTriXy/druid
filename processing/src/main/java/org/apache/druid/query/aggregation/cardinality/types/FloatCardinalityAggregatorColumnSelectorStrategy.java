/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.query.aggregation.cardinality.types;

import com.google.common.hash.Hasher;
import org.apache.druid.hll.HyperLogLogCollector;
import org.apache.druid.query.aggregation.cardinality.CardinalityAggregator;
import org.apache.druid.segment.BaseFloatColumnValueSelector;

public class FloatCardinalityAggregatorColumnSelectorStrategy
    implements CardinalityAggregatorColumnSelectorStrategy<BaseFloatColumnValueSelector>
{
  public static void addFloatToCollector(final HyperLogLogCollector collector, final float n)
  {
    collector.add(CardinalityAggregator.HASH_FUNCTION.hashInt(Float.floatToIntBits(n)).asBytes());
  }

  @Override
  public void hashRow(BaseFloatColumnValueSelector selector, Hasher hasher)
  {
    if (!selector.isNull()) {
      hasher.putFloat(selector.getFloat());
    }
  }

  @Override
  public void hashValues(BaseFloatColumnValueSelector selector, HyperLogLogCollector collector)
  {
    if (!selector.isNull()) {
      addFloatToCollector(collector, selector.getFloat());
    }
  }
}
