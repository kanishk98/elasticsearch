/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.search.aggregations.metrics;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.Scorable;
import org.apache.lucene.search.ScoreMode;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.lease.Releasables;
import org.elasticsearch.common.util.CollectionUtils;
import org.elasticsearch.common.util.ObjectArray;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptedMetricAggContexts;
import org.elasticsearch.script.ScriptedMetricAggContexts.MapScript;
import org.elasticsearch.search.aggregations.Aggregator;
import org.elasticsearch.search.aggregations.InternalAggregation;
import org.elasticsearch.search.aggregations.LeafBucketCollector;
import org.elasticsearch.search.aggregations.LeafBucketCollectorBase;
import org.elasticsearch.search.internal.SearchContext;
import org.elasticsearch.search.lookup.SearchLookup;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

class ScriptedMetricAggregator extends MetricsAggregator {
    /**
     * Estimated cost to maintain a bucket. Since this aggregator uses
     * untracked java collections for its state it is going to both be
     * much "heavier" than a normal metric aggregator and not going to be
     * tracked by the circuit breakers properly. This is sad. So we pick a big
     * number and estimate that each bucket costs that. It could be wildly
     * inaccurate. We're sort of hoping that the real memory breaker saves
     * us here. Or that folks just don't use the aggregation.
     */
    private static final long BUCKET_COST_ESTIMATE = 1024 * 5;

    private final SearchLookup lookup;
    private final Map<String, Object> initialState;
    private final ScriptedMetricAggContexts.MapScript.Factory mapScriptFactory;
    private final Map<String, Object> mapScriptParams;
    private final ScriptedMetricAggContexts.CombineScript.Factory combineScriptFactory;
    private final Map<String, Object> combineScriptParams;
    private final Script reduceScript;
    private ObjectArray<State> states;

    ScriptedMetricAggregator(
        String name,
        SearchLookup lookup,
        Map<String, Object> initialState,
        ScriptedMetricAggContexts.MapScript.Factory mapScriptFactory,
        Map<String, Object> mapScriptParams,
        ScriptedMetricAggContexts.CombineScript.Factory combineScriptFactory,
        Map<String, Object> combineScriptParams,
        Script reduceScript,
        SearchContext context,
        Aggregator parent,
        Map<String, Object> metadata
    ) throws IOException {
        super(name, context, parent, metadata);
        this.lookup = lookup;
        this.initialState = initialState;
        this.mapScriptFactory = mapScriptFactory;
        this.mapScriptParams = mapScriptParams;
        this.combineScriptFactory = combineScriptFactory;
        this.combineScriptParams = combineScriptParams;
        this.reduceScript = reduceScript;
        states = context.bigArrays().newObjectArray(1);
    }

    @Override
    public ScoreMode scoreMode() {
        return ScoreMode.COMPLETE; // TODO: how can we know if the script relies on scores?
    }

    @Override
    public LeafBucketCollector getLeafCollector(LeafReaderContext ctx, LeafBucketCollector sub) throws IOException {
        // Clear any old leaf scripts so we rebuild them on the new leaf when we first see them.
        for (long i = 0; i < states.size(); i++) {
            State state = states.get(i);
            if (state == null) {
                continue;
            }
            state.leafMapScript = null;
        }
        return new LeafBucketCollectorBase(sub, null) {
            private Scorable scorer;

            @Override
            public void setScorer(Scorable scorer) throws IOException {
                this.scorer = scorer;
            }

            @Override
            public void collect(int doc, long owningBucketOrd) throws IOException {
                states = context.bigArrays().grow(states, owningBucketOrd + 1);
                State state = states.get(owningBucketOrd);
                if (state == null) {
                    addRequestCircuitBreakerBytes(BUCKET_COST_ESTIMATE);
                    state = new State();
                    states.set(owningBucketOrd, state);
                }
                if (state.leafMapScript == null) {
                    state.leafMapScript = state.mapScript.newInstance(ctx);
                    state.leafMapScript.setScorer(scorer);
                }
                state.leafMapScript.setDocument(doc);
                state.leafMapScript.execute();
            }
        };
    }

    @Override
    public InternalAggregation buildAggregation(long owningBucketOrdinal) {
        Object result = resultFor(aggStateFor(owningBucketOrdinal));
        StreamOutput.checkWriteable(result);
        return new InternalScriptedMetric(name, result, reduceScript, metadata());
    }

    private Map<String, Object> aggStateFor(long owningBucketOrdinal) {
        if (owningBucketOrdinal >= states.size()) {
            return newInitialState();
        }
        State state = states.get(owningBucketOrdinal);
        if (state == null) {
            return newInitialState();
        }
        // The last script that touched the state at this point is the "map" script
        CollectionUtils.ensureNoSelfReferences(state.aggState, "Scripted metric aggs map script");
        return state.aggState;
    }

    private Object resultFor(Map<String, Object> aggState) {
        if (combineScriptFactory == null) {
            return aggState;
        }
        Object result = combineScriptFactory.newInstance(
            // Send a deep copy of the params because the script is allowed to mutate it
            ScriptedMetricAggregatorFactory.deepCopyParams(combineScriptParams, context),
            aggState
        ).execute();
        CollectionUtils.ensureNoSelfReferences(result, "Scripted metric aggs combine script");
        return result;
    }

    private Map<String, Object> newInitialState() {
        return initialState == null ? new HashMap<>() : ScriptedMetricAggregatorFactory.deepCopyParams(initialState, context);
    }

    @Override
    public InternalAggregation buildEmptyAggregation() {
        return new InternalScriptedMetric(name, null, reduceScript, metadata());
    }

    @Override
    public void close() {
        Releasables.close(states);
    }

    private class State {
        private final ScriptedMetricAggContexts.MapScript.LeafFactory mapScript;
        private final Map<String, Object> aggState;
        private MapScript leafMapScript;

        State() {
            aggState = newInitialState();
            mapScript = mapScriptFactory.newFactory(
                // Send a deep copy of the params because the script is allowed to mutate it
                ScriptedMetricAggregatorFactory.deepCopyParams(mapScriptParams, context),
                aggState,
                lookup
            );
        }
    }
}
