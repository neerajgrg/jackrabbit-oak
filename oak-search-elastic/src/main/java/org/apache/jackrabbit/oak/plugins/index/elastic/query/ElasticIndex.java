/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.oak.plugins.index.elastic.query;

import org.apache.jackrabbit.oak.commons.PathUtils;
import org.apache.jackrabbit.oak.plugins.index.elastic.ElasticConnection;
import org.apache.jackrabbit.oak.plugins.index.elastic.query.async.ElasticRequestHandler;
import org.apache.jackrabbit.oak.plugins.index.search.IndexNode;
import org.apache.jackrabbit.oak.plugins.index.search.SizeEstimator;
import org.apache.jackrabbit.oak.plugins.index.search.spi.query.FulltextIndex;
import org.apache.jackrabbit.oak.plugins.index.search.spi.query.FulltextIndexPlanner;
import org.apache.jackrabbit.oak.plugins.index.search.util.LMSEstimator;
import org.apache.jackrabbit.oak.spi.query.Cursor;
import org.apache.jackrabbit.oak.spi.query.Filter;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.elasticsearch.common.Strings;
import org.elasticsearch.index.query.QueryBuilder;
import org.jetbrains.annotations.NotNull;

import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Predicate;

import static org.apache.jackrabbit.oak.plugins.index.IndexConstants.TYPE_PROPERTY_NAME;
import static org.apache.jackrabbit.oak.plugins.index.elastic.ElasticIndexDefinition.TYPE_ELASTICSEARCH;

class ElasticIndex extends FulltextIndex {
    private static final Predicate<NodeState> ELASTICSEARCH_INDEX_DEFINITION_PREDICATE =
            state -> TYPE_ELASTICSEARCH.equals(state.getString(TYPE_PROPERTY_NAME));
    private static final Map<String, LMSEstimator> ESTIMATORS = new WeakHashMap<>();

    // higher than some threshold below which the query should rather be answered by something else if possible
    private static final double MIN_COST = 100.1;

    private final ElasticConnection elasticConnection;
    private final NodeState root;

    ElasticIndex(@NotNull ElasticConnection elasticConnection, @NotNull NodeState root) {
        this.elasticConnection = elasticConnection;
        this.root = root;
    }

    @Override
    protected String getType() {
        return TYPE_ELASTICSEARCH;
    }

    @Override
    protected SizeEstimator getSizeEstimator(IndexPlan plan) {
        return () -> getEstimator(plan.getPlanName()).estimate(plan.getFilter());
    }

    @Override
    protected Predicate<NodeState> getIndexDefinitionPredicate() {
        return ELASTICSEARCH_INDEX_DEFINITION_PREDICATE;
    }

    @Override
    public double getMinimumCost() {
        return MIN_COST;
    }

    @Override
    public String getIndexName() {
        return "elasticsearch";
    }

    @Override
    protected ElasticIndexNode acquireIndexNode(IndexPlan plan) {
        return (ElasticIndexNode) super.acquireIndexNode(plan);
    }

    @Override
    protected IndexNode acquireIndexNode(String indexPath) {
        return new ElasticIndexNode(root, indexPath, elasticConnection);
    }

    @Override
    protected String getFulltextRequestString(IndexPlan plan, IndexNode indexNode) {
        return Strings.toString(new ElasticRequestHandler(plan, getPlanResult(plan)).build());
    }

    @Override
    public Cursor query(IndexPlan plan, NodeState rootState) {
        final Filter filter = plan.getFilter();

        // TODO: sorting

        final FulltextIndexPlanner.PlanResult pr = getPlanResult(plan);

        // this function is called for each extracted row. Passing FulltextIndex::shouldInclude means that for each
        // row we evaluate getPathRestriction(plan) & plan.getFilter().getPathRestriction(). Providing a partial
        // function (https://en.wikipedia.org/wiki/Partial_function) we can evaluate them once and still use a predicate as before
//        BiFunction<String, Filter.PathRestriction, Predicate<String>> partialShouldInclude = (path, pathRestriction) -> docPath ->
//                shouldInclude(path, pathRestriction, docPath);
//
//        Iterator<FulltextResultRow> itr = new ElasticResultRowAsyncIterator(
//                acquireIndexNode(plan),
//                plan,
//                pr,
//                partialShouldInclude.apply(getPathRestriction(plan), plan.getFilter().getPathRestriction()),
//                getEstimator(plan.getPlanName())
//        );

        Iterator<FulltextResultRow> itr = new ElasticResultRowIterator(filter, pr, plan,
                acquireIndexNode(plan), FulltextIndex::shouldInclude, getEstimator(plan.getPlanName()));

        /*
        TODO: sync (nrt too??)
        if (pr.hasPropertyIndexResult() || pr.evaluateSyncNodeTypeRestriction()) {
            itr = mergePropertyIndexResult(plan, rootState, itr);
        }
        */

        // no concept of rewound in ES (even if it might be doing it internally, we can't do much about it
        IteratorRewoundStateProvider rewoundStateProvider = () -> 0;
        return new FulltextPathCursor(itr, rewoundStateProvider, plan, filter.getQueryLimits(), getSizeEstimator(plan));
    }

    private LMSEstimator getEstimator(String path) {
        ESTIMATORS.putIfAbsent(path, new LMSEstimator());
        return ESTIMATORS.get(path);
    }

    private static boolean shouldInclude(String path, Filter.PathRestriction pathRestriction, String docPath) {
        boolean include = true;
        switch (pathRestriction) {
            case EXACT:
                include = path.equals(docPath);
                break;
            case DIRECT_CHILDREN:
                include = PathUtils.getParentPath(docPath).equals(path);
                break;
            case ALL_CHILDREN:
                include = PathUtils.isAncestor(path, docPath);
                break;
        }

        return include;
    }

    @Override
    protected boolean filterReplacedIndexes() {
        return true;
    }
}
