/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

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

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.index.rankeval;

import org.opensearch.action.OriginalIndices;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.common.xcontent.json.JsonXContent;
import org.opensearch.core.common.Strings;
import org.opensearch.core.common.bytes.BytesReference;
import org.opensearch.core.common.io.stream.NamedWriteableRegistry;
import org.opensearch.core.index.shard.ShardId;
import org.opensearch.core.xcontent.MediaTypeRegistry;
import org.opensearch.core.xcontent.ToXContent;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.core.xcontent.XContentParseException;
import org.opensearch.core.xcontent.XContentParser;
import org.opensearch.search.SearchHit;
import org.opensearch.search.SearchShardTarget;
import org.opensearch.test.OpenSearchTestCase;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.opensearch.index.rankeval.EvaluationMetric.filterUnratedDocuments;
import static org.opensearch.test.EqualsHashCodeTestUtils.checkEqualsAndHashCode;
import static org.opensearch.test.XContentTestUtils.insertRandomFields;
import static org.hamcrest.CoreMatchers.containsString;

public class DiscountedCumulativeGainTests extends OpenSearchTestCase {

    static final double EXPECTED_DCG = 13.84826362927298;
    static final double EXPECTED_IDCG = 14.595390756454922;
    static final double EXPECTED_NDCG = EXPECTED_DCG / EXPECTED_IDCG;
    private static final double DELTA = 10E-14;

    /**
     * Assuming the docs are ranked in the following order:
     * <p>
     * rank | relevance | 2^(relevance) - 1 | log_2(rank + 1) | (2^(relevance) - 1) / log_2(rank + 1)
     * -------------------------------------------------------------------------------------------
     * 1 | 3 | 7.0 | 1.0 | 7.0 | 7.0 | 
     * 2 | 2 | 3.0 | 1.5849625007211563 | 1.8927892607143721
     * 3 | 3 | 7.0 | 2.0 | 3.5
     * 4 | 0 | 0.0 | 2.321928094887362 | 0.0
     * 5 | 1 | 1.0 | 2.584962500721156 | 0.38685280723454163
     * 6 | 2 | 3.0 | 2.807354922057604 | 1.0686215613240666
     * <p>
     * dcg = 13.84826362927298 (sum of last column)
     */
    public void testDCGAt() {
        List<RatedDocument> rated = new ArrayList<>();
        int[] relevanceRatings = new int[] { 3, 2, 3, 0, 1, 2 };
        SearchHit[] hits = new SearchHit[6];
        for (int i = 0; i < 6; i++) {
            rated.add(new RatedDocument("index", Integer.toString(i), relevanceRatings[i]));
            hits[i] = new SearchHit(i, Integer.toString(i), Collections.emptyMap(), Collections.emptyMap());
            hits[i].shard(new SearchShardTarget("testnode", new ShardId("index", "uuid", 0), null, OriginalIndices.NONE));
        }
        DiscountedCumulativeGain dcg = new DiscountedCumulativeGain();
        assertEquals(EXPECTED_DCG, dcg.evaluate("id", hits, rated).metricScore(), DELTA);

        /*
          Check with normalization: to get the maximal possible dcg, sort documents by
          relevance in descending order
        
          rank | relevance | 2^(relevance) - 1 | log_2(rank + 1) | (2^(relevance) - 1) / log_2(rank + 1)
          ---------------------------------------------------------------------------------------
          1 | 3 | 7.0 | 1.0  | 7.0
          2 | 3 | 7.0 | 1.5849625007211563 | 4.416508275000202
          3 | 2 | 3.0 | 2.0  | 1.5
          4 | 2 | 3.0 | 2.321928094887362 | 1.2920296742201793
          5 | 1 | 1.0 | 2.584962500721156  | 0.38685280723454163
          6 | 0 | 0.0 | 2.807354922057604  | 0.0
        
          idcg = 14.595390756454922 (sum of last column)
         */
        dcg = new DiscountedCumulativeGain(true, null, 10);
        assertEquals(EXPECTED_NDCG, dcg.evaluate("id", hits, rated).metricScore(), DELTA);
    }

    /**
     * This tests metric when some documents in the search result don't have a
     * rating provided by the user.
     * <p>
     * rank | relevance | 2^(relevance) - 1 | log_2(rank + 1) | (2^(relevance) - 1) / log_2(rank + 1)
     * -------------------------------------------------------------------------------------------
     * 1 | 3 | 7.0 | 1.0 | 7.0 2 | 
     * 2 | 3.0 | 1.5849625007211563 | 1.8927892607143721
     * 3 | 3 | 7.0 | 2.0 | 3.5
     * 4 | n/a | n/a | n/a | n/a
     * 5 | 1 | 1.0 | 2.584962500721156 | 0.38685280723454163
     * 6 | n/a | n/a | n/a | n/a
     * <p>
     * dcg = 12.779642067948913 (sum of last column)
     */
    public void testDCGAtSixMissingRatings() {
        List<RatedDocument> rated = new ArrayList<>();
        Integer[] relevanceRatings = new Integer[] { 3, 2, 3, null, 1 };
        SearchHit[] hits = new SearchHit[6];
        for (int i = 0; i < 6; i++) {
            if (i < relevanceRatings.length) {
                if (relevanceRatings[i] != null) {
                    rated.add(new RatedDocument("index", Integer.toString(i), relevanceRatings[i]));
                }
            }
            hits[i] = new SearchHit(i, Integer.toString(i), Collections.emptyMap(), Collections.emptyMap());
            hits[i].shard(new SearchShardTarget("testnode", new ShardId("index", "uuid", 0), null, OriginalIndices.NONE));
        }
        DiscountedCumulativeGain dcg = new DiscountedCumulativeGain();
        EvalQueryQuality result = dcg.evaluate("id", hits, rated);
        assertEquals(12.779642067948913, result.metricScore(), DELTA);
        assertEquals(2, filterUnratedDocuments(result.getHitsAndRatings()).size());

        /*
          Check with normalization: to get the maximal possible dcg, sort documents by
          relevance in descending order
        
          rank | relevance | 2^(relevance) - 1 | log_2(rank + 1) | (2^(relevance) - 1) / log_2(rank + 1)
          ----------------------------------------------------------------------------------------
          1 | 3 | 7.0 | 1.0  | 7.0
          2 | 3 | 7.0 | 1.5849625007211563 | 4.416508275000202
          3 | 2 | 3.0 | 2.0  | 1.5
          4 | 1 | 1.0 | 2.321928094887362   | 0.43067655807339
          5 | n.a | n.a | n.a.  | n.a.
          6 | n.a | n.a | n.a  | n.a
        
          idcg = 13.347184833073591 (sum of last column)
         */
        dcg = new DiscountedCumulativeGain(true, null, 10);
        assertEquals(12.779642067948913 / 13.347184833073591, dcg.evaluate("id", hits, rated).metricScore(), DELTA);
    }

    /**
     * This tests that normalization works as expected when there are more rated
     * documents than search hits because we restrict DCG to be calculated at the
     * fourth position
     * <p>
     * rank | relevance | 2^(relevance) - 1 | log_2(rank + 1) | (2^(relevance) - 1) / log_2(rank + 1)
     * -------------------------------------------------------------------------------------------
     * 1 | 3 | 7.0 | 1.0 | 7.0 2 | 
     * 2 | 3.0 | 1.5849625007211563 | 1.8927892607143721
     * 3 | 3 | 7.0 | 2.0 | 3.5
     * 4 | n/a | n/a | n/a | n/a
     * -----------------------------------------------------------------
     * 5 | 1 | 1.0 | 2.584962500721156 | 0.38685280723454163
     * 6 | n/a | n/a | n/a | n/a
     * <p>
     * dcg = 12.392789260714371 (sum of last column until position 4)
     */
    public void testDCGAtFourMoreRatings() {
        Integer[] relevanceRatings = new Integer[] { 3, 2, 3, null, 1, null };
        List<RatedDocument> ratedDocs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            if (i < relevanceRatings.length) {
                if (relevanceRatings[i] != null) {
                    ratedDocs.add(new RatedDocument("index", Integer.toString(i), relevanceRatings[i]));
                }
            }
        }
        // only create four hits
        SearchHit[] hits = new SearchHit[4];
        for (int i = 0; i < 4; i++) {
            hits[i] = new SearchHit(i, Integer.toString(i), Collections.emptyMap(), Collections.emptyMap());
            hits[i].shard(new SearchShardTarget("testnode", new ShardId("index", "uuid", 0), null, OriginalIndices.NONE));
        }
        DiscountedCumulativeGain dcg = new DiscountedCumulativeGain();
        EvalQueryQuality result = dcg.evaluate("id", hits, ratedDocs);
        assertEquals(12.392789260714371, result.metricScore(), DELTA);
        assertEquals(1, filterUnratedDocuments(result.getHitsAndRatings()).size());

        /*
          Check with normalization: to get the maximal possible dcg, sort documents by
          relevance in descending order
        
          rank | relevance | 2^(relevance) - 1 | log_2(rank + 1) | (2^(relevance) - 1) / log_2(rank + 1)
          ---------------------------------------------------------------------------------------
          1 | 3 | 7.0 | 1.0  | 7.0
          2 | 3 | 7.0 | 1.5849625007211563 | 4.416508275000202
          3 | 2 | 3.0 | 2.0  | 1.5
          4 | 1 | 1.0 | 2.321928094887362   | 0.43067655807339
          ---------------------------------------------------------------------------------------
          5 | n.a | n.a | n.a.  | n.a.
          6 | n.a | n.a | n.a  | n.a
        
          idcg = 13.347184833073591 (sum of last column)
         */
        dcg = new DiscountedCumulativeGain(true, null, 10);
        assertEquals(12.392789260714371 / 13.347184833073591, dcg.evaluate("id", hits, ratedDocs).metricScore(), DELTA);
    }

    /**
     * test that metric returns 0.0 when there are no search results
     */
    public void testNoResults() throws Exception {
        Integer[] relevanceRatings = new Integer[] { 3, 2, 3, null, 1, null };
        List<RatedDocument> ratedDocs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            if (i < relevanceRatings.length) {
                if (relevanceRatings[i] != null) {
                    ratedDocs.add(new RatedDocument("index", Integer.toString(i), relevanceRatings[i]));
                }
            }
        }
        SearchHit[] hits = new SearchHit[0];
        DiscountedCumulativeGain dcg = new DiscountedCumulativeGain();
        EvalQueryQuality result = dcg.evaluate("id", hits, ratedDocs);
        assertEquals(0.0d, result.metricScore(), DELTA);
        assertEquals(0, filterUnratedDocuments(result.getHitsAndRatings()).size());

        // also check normalized
        dcg = new DiscountedCumulativeGain(true, null, 10);
        result = dcg.evaluate("id", hits, ratedDocs);
        assertEquals(0.0d, result.metricScore(), DELTA);
        assertEquals(0, filterUnratedDocuments(result.getHitsAndRatings()).size());
    }

    public void testParseFromXContent() throws IOException {
        assertParsedCorrect("{ \"unknown_doc_rating\": 2, \"normalize\": true, \"k\" : 15 }", 2, true, 15);
        assertParsedCorrect("{ \"normalize\": false, \"k\" : 15 }", null, false, 15);
        assertParsedCorrect("{ \"unknown_doc_rating\": 2, \"k\" : 15 }", 2, false, 15);
        assertParsedCorrect("{ \"unknown_doc_rating\": 2, \"normalize\": true }", 2, true, 10);
        assertParsedCorrect("{ \"normalize\": true }", null, true, 10);
        assertParsedCorrect("{ \"k\": 23 }", null, false, 23);
        assertParsedCorrect("{ \"unknown_doc_rating\": 2 }", 2, false, 10);
    }

    private void assertParsedCorrect(String xContent, Integer expectedUnknownDocRating, boolean expectedNormalize, int expectedK)
        throws IOException {
        try (XContentParser parser = createParser(JsonXContent.jsonXContent, xContent)) {
            DiscountedCumulativeGain dcgAt = DiscountedCumulativeGain.fromXContent(parser);
            assertEquals(expectedUnknownDocRating, dcgAt.getUnknownDocRating());
            assertEquals(expectedNormalize, dcgAt.getNormalize());
            assertEquals(expectedK, dcgAt.getK());
        }
    }

    public static DiscountedCumulativeGain createTestItem() {
        boolean normalize = randomBoolean();
        Integer unknownDocRating = frequently() ? Integer.valueOf(randomIntBetween(0, 1000)) : null;
        return new DiscountedCumulativeGain(normalize, unknownDocRating, randomIntBetween(1, 10));
    }

    public void testXContentRoundtrip() throws IOException {
        DiscountedCumulativeGain testItem = createTestItem();
        XContentBuilder builder = MediaTypeRegistry.contentBuilder(randomFrom(XContentType.values()));
        XContentBuilder shuffled = shuffleXContent(testItem.toXContent(builder, ToXContent.EMPTY_PARAMS));
        try (XContentParser itemParser = createParser(shuffled)) {
            itemParser.nextToken();
            itemParser.nextToken();
            DiscountedCumulativeGain parsedItem = DiscountedCumulativeGain.fromXContent(itemParser);
            assertNotSame(testItem, parsedItem);
            assertEquals(testItem, parsedItem);
            assertEquals(testItem.hashCode(), parsedItem.hashCode());
        }
    }

    public void testXContentParsingIsNotLenient() throws IOException {
        DiscountedCumulativeGain testItem = createTestItem();
        XContentType xContentType = randomFrom(XContentType.values());
        BytesReference originalBytes = toShuffledXContent(testItem, xContentType, ToXContent.EMPTY_PARAMS, randomBoolean());
        BytesReference withRandomFields = insertRandomFields(xContentType, originalBytes, null, random());
        try (XContentParser parser = createParser(xContentType.xContent(), withRandomFields)) {
            parser.nextToken();
            parser.nextToken();
            XContentParseException exception = expectThrows(
                XContentParseException.class,
                () -> DiscountedCumulativeGain.fromXContent(parser)
            );
            assertThat(exception.getMessage(), containsString("[dcg] unknown field"));
        }
    }

    public void testMetricDetails() {
        double dcg = randomDoubleBetween(0, 1, true);
        double idcg = randomBoolean() ? 0.0 : randomDoubleBetween(0, 1, true);
        double expectedNdcg = idcg != 0 ? dcg / idcg : 0.0;
        int unratedDocs = randomIntBetween(0, 100);
        DiscountedCumulativeGain.Detail detail = new DiscountedCumulativeGain.Detail(dcg, idcg, unratedDocs);
        assertEquals(dcg, detail.getDCG(), 0.0);
        assertEquals(idcg, detail.getIDCG(), 0.0);
        assertEquals(expectedNdcg, detail.getNDCG(), 0.0);
        assertEquals(unratedDocs, detail.getUnratedDocs());
        if (idcg != 0) {
            assertEquals(
                "{\"dcg\":{\"dcg\":"
                    + dcg
                    + ",\"ideal_dcg\":"
                    + idcg
                    + ",\"normalized_dcg\":"
                    + expectedNdcg
                    + ",\"unrated_docs\":"
                    + unratedDocs
                    + "}}",
                Strings.toString(MediaTypeRegistry.JSON, detail)
            );
        } else {
            assertEquals(
                "{\"dcg\":{\"dcg\":" + dcg + ",\"unrated_docs\":" + unratedDocs + "}}",
                Strings.toString(MediaTypeRegistry.JSON, detail)
            );
        }
    }

    public void testSerialization() throws IOException {
        DiscountedCumulativeGain original = createTestItem();
        DiscountedCumulativeGain deserialized = OpenSearchTestCase.copyWriteable(
            original,
            new NamedWriteableRegistry(Collections.emptyList()),
            DiscountedCumulativeGain::new
        );
        assertEquals(deserialized, original);
        assertEquals(deserialized.hashCode(), original.hashCode());
        assertNotSame(deserialized, original);
    }

    public void testEqualsAndHash() throws IOException {
        checkEqualsAndHashCode(createTestItem(), original -> {
            return new DiscountedCumulativeGain(original.getNormalize(), original.getUnknownDocRating(), original.getK());
        }, DiscountedCumulativeGainTests::mutateTestItem);
    }

    private static DiscountedCumulativeGain mutateTestItem(DiscountedCumulativeGain original) {
        switch (randomIntBetween(0, 2)) {
            case 0:
                return new DiscountedCumulativeGain(!original.getNormalize(), original.getUnknownDocRating(), original.getK());
            case 1:
                return new DiscountedCumulativeGain(
                    original.getNormalize(),
                    randomValueOtherThan(original.getUnknownDocRating(), () -> randomIntBetween(0, 10)),
                    original.getK()
                );
            case 2:
                return new DiscountedCumulativeGain(
                    original.getNormalize(),
                    original.getUnknownDocRating(),
                    randomValueOtherThan(original.getK(), () -> randomIntBetween(1, 10))
                );
            default:
                throw new IllegalArgumentException("mutation variant not allowed");
        }
    }
}
