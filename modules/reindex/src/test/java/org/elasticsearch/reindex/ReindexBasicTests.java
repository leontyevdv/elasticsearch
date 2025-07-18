/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.reindex;

import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.reindex.AbstractBulkByScrollRequest;
import org.elasticsearch.index.reindex.BulkByScrollResponse;
import org.elasticsearch.index.reindex.ReindexRequestBuilder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.elasticsearch.index.IndexSettings.SYNTHETIC_VECTORS;
import static org.elasticsearch.index.query.QueryBuilders.termQuery;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertHitCount;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

public class ReindexBasicTests extends ReindexTestCase {
    public void testFiltering() throws Exception {
        indexRandom(
            true,
            prepareIndex("source").setId("1").setSource("foo", "a"),
            prepareIndex("source").setId("2").setSource("foo", "a"),
            prepareIndex("source").setId("3").setSource("foo", "b"),
            prepareIndex("source").setId("4").setSource("foo", "c")
        );
        assertHitCount(prepareSearch("source").setSize(0), 4);

        // Copy all the docs
        ReindexRequestBuilder copy = reindex().source("source").destination("dest").refresh(true);
        assertThat(copy.get(), matcher().created(4));
        assertHitCount(prepareSearch("dest").setSize(0), 4);

        // Now none of them
        createIndex("none");
        copy = reindex().source("source").destination("none").filter(termQuery("foo", "no_match")).refresh(true);
        assertThat(copy.get(), matcher().created(0));
        assertHitCount(prepareSearch("none").setSize(0), 0);

        // Now half of them
        copy = reindex().source("source").destination("dest_half").filter(termQuery("foo", "a")).refresh(true);
        assertThat(copy.get(), matcher().created(2));
        assertHitCount(prepareSearch("dest_half").setSize(0), 2);

        // Limit with maxDocs
        copy = reindex().source("source").destination("dest_size_one").maxDocs(1).refresh(true);
        assertThat(copy.get(), matcher().created(1));
        assertHitCount(prepareSearch("dest_size_one").setSize(0), 1);
    }

    public void testCopyMany() throws Exception {
        List<IndexRequestBuilder> docs = new ArrayList<>();
        int max = between(150, 500);
        for (int i = 0; i < max; i++) {
            docs.add(prepareIndex("source").setId(Integer.toString(i)).setSource("foo", "a"));
        }

        indexRandom(true, docs);
        assertHitCount(prepareSearch("source").setSize(0), max);

        // Copy all the docs
        ReindexRequestBuilder copy = reindex().source("source").destination("dest").refresh(true);
        // Use a small batch size so we have to use more than one batch
        copy.source().setSize(5);
        assertThat(copy.get(), matcher().created(max).batches(max, 5));
        assertHitCount(prepareSearch("dest").setSize(0), max);

        // Copy some of the docs
        int half = max / 2;
        copy = reindex().source("source").destination("dest_half").refresh(true);
        // Use a small batch size so we have to use more than one batch
        copy.source().setSize(5);
        copy.maxDocs(half);
        assertThat(copy.get(), matcher().created(half).batches(half, 5));
        assertHitCount(prepareSearch("dest_half").setSize(0), half);
    }

    public void testCopyManyWithSlices() throws Exception {
        List<IndexRequestBuilder> docs = new ArrayList<>();
        int max = between(150, 500);
        for (int i = 0; i < max; i++) {
            docs.add(prepareIndex("source").setId(Integer.toString(i)).setSource("foo", "a"));
        }

        indexRandom(true, docs);
        assertHitCount(prepareSearch("source").setSize(0), max);

        int slices = randomSlices();
        int expectedSlices = expectedSliceStatuses(slices, "source");

        // Copy all the docs
        ReindexRequestBuilder copy = reindex().source("source").destination("dest").refresh(true).setSlices(slices);
        // Use a small batch size so we have to use more than one batch
        copy.source().setSize(5);
        assertThat(copy.get(), matcher().created(max).batches(greaterThanOrEqualTo(max / 5)).slices(hasSize(expectedSlices)));
        assertHitCount(prepareSearch("dest").setSize(0), max);

        // Copy some of the docs
        int half = max / 2;
        copy = reindex().source("source").destination("dest_half").refresh(true).setSlices(slices);
        // Use a small batch size so we have to use more than one batch
        copy.source().setSize(5);
        copy.maxDocs(half);
        BulkByScrollResponse response = copy.get();
        assertThat(response, matcher().created(lessThanOrEqualTo((long) half)).slices(hasSize(expectedSlices)));
        assertHitCount(prepareSearch("dest_half").setSize(0), response.getCreated());
    }

    public void testMultipleSources() throws Exception {
        int sourceIndices = between(2, 5);

        Map<String, List<IndexRequestBuilder>> docs = new HashMap<>();
        for (int sourceIndex = 0; sourceIndex < sourceIndices; sourceIndex++) {
            String indexName = "source" + sourceIndex;
            String typeName = "test" + sourceIndex;
            docs.put(indexName, new ArrayList<>());
            int numDocs = between(50, 200);
            for (int i = 0; i < numDocs; i++) {
                docs.get(indexName).add(prepareIndex(indexName).setId("id_" + sourceIndex + "_" + i).setSource("foo", "a"));
            }
        }

        List<IndexRequestBuilder> allDocs = docs.values().stream().flatMap(Collection::stream).collect(Collectors.toList());
        indexRandom(true, allDocs);
        for (Map.Entry<String, List<IndexRequestBuilder>> entry : docs.entrySet()) {
            assertHitCount(prepareSearch(entry.getKey()).setSize(0), entry.getValue().size());
        }

        int slices = randomSlices(1, 10);
        int expectedSlices = expectedSliceStatuses(slices, docs.keySet());

        String[] sourceIndexNames = docs.keySet().toArray(new String[docs.size()]);
        ReindexRequestBuilder request = reindex().source(sourceIndexNames).destination("dest").refresh(true).setSlices(slices);

        BulkByScrollResponse response = request.get();
        assertThat(response, matcher().created(allDocs.size()).slices(hasSize(expectedSlices)));
        assertHitCount(prepareSearch("dest").setSize(0), allDocs.size());
    }

    public void testMissingSources() {
        BulkByScrollResponse response = updateByQuery().source("missing-index-*")
            .refresh(true)
            .setSlices(AbstractBulkByScrollRequest.AUTO_SLICES)
            .get();
        assertThat(response, matcher().created(0).slices(hasSize(0)));
    }

    public void testReindexFromComplexDateMathIndexName() throws Exception {
        String sourceIndexName = "datemath-2001-01-01-14";
        String destIndexName = "<reindex-datemath-{2001-01-01-13||+1h/h{yyyy-MM-dd-HH|-07:00}}>";
        indexRandom(
            true,
            prepareIndex(sourceIndexName).setId("1").setSource("foo", "a"),
            prepareIndex(sourceIndexName).setId("2").setSource("foo", "a"),
            prepareIndex(sourceIndexName).setId("3").setSource("foo", "b"),
            prepareIndex(sourceIndexName).setId("4").setSource("foo", "c")
        );
        assertHitCount(prepareSearch(sourceIndexName).setSize(0), 4);

        // Copy all the docs
        ReindexRequestBuilder copy = reindex().source(sourceIndexName).destination(destIndexName).refresh(true);
        assertThat(copy.get(), matcher().created(4));
        assertHitCount(prepareSearch(destIndexName).setSize(0), 4);
    }

    public void testReindexIncludeVectors() throws Exception {
        assumeTrue("This test requires synthetic vectors to be enabled", SYNTHETIC_VECTORS);
        var resp1 = prepareCreate("test").setSettings(
            Settings.builder().put(IndexSettings.INDEX_MAPPING_SOURCE_SYNTHETIC_VECTORS_SETTING.getKey(), true).build()
        ).setMapping("foo", "type=dense_vector,similarity=l2_norm", "bar", "type=sparse_vector").get();
        assertAcked(resp1);

        var resp2 = prepareCreate("test_reindex").setSettings(
            Settings.builder().put(IndexSettings.INDEX_MAPPING_SOURCE_SYNTHETIC_VECTORS_SETTING.getKey(), true).build()
        ).setMapping("foo", "type=dense_vector,similarity=l2_norm", "bar", "type=sparse_vector").get();
        assertAcked(resp2);

        indexRandom(
            true,
            prepareIndex("test").setId("1").setSource("foo", List.of(3f, 2f, 1.5f), "bar", Map.of("token_1", 4f, "token_2", 7f))
        );

        var searchResponse = prepareSearch("test").get();
        try {
            assertThat(searchResponse.getHits().getTotalHits().value(), equalTo(1L));
            assertThat(searchResponse.getHits().getHits().length, equalTo(1));
            var sourceMap = searchResponse.getHits().getAt(0).getSourceAsMap();
            assertThat(sourceMap.size(), equalTo(0));
        } finally {
            searchResponse.decRef();
        }

        // Copy all the docs
        ReindexRequestBuilder copy = reindex().source("test").destination("test_reindex").refresh(true);
        var reindexResponse = copy.get();
        assertThat(reindexResponse, matcher().created(1));

        searchResponse = prepareSearch("test_reindex").get();
        try {
            assertThat(searchResponse.getHits().getTotalHits().value(), equalTo(1L));
            assertThat(searchResponse.getHits().getHits().length, equalTo(1));
            var sourceMap = searchResponse.getHits().getAt(0).getSourceAsMap();
            assertThat(sourceMap.size(), equalTo(0));
        } finally {
            searchResponse.decRef();
        }

        searchResponse = prepareSearch("test_reindex").setExcludeVectors(false).get();
        try {
            assertThat(searchResponse.getHits().getTotalHits().value(), equalTo(1L));
            assertThat(searchResponse.getHits().getHits().length, equalTo(1));
            var sourceMap = searchResponse.getHits().getAt(0).getSourceAsMap();
            assertThat(sourceMap.get("foo"), anyOf(equalTo(List.of(3f, 2f, 1.5f)), equalTo(List.of(3d, 2d, 1.5d))));
            assertThat(
                sourceMap.get("bar"),
                anyOf(equalTo(Map.of("token_1", 4f, "token_2", 7f)), equalTo(Map.of("token_1", 4d, "token_2", 7d)))
            );
        } finally {
            searchResponse.decRef();
        }
    }

}
