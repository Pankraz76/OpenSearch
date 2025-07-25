/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.indices.settings;

import org.opensearch.action.search.SearchPhaseExecutionException;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.action.support.WriteRequest;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.metadata.Metadata;
import org.opensearch.cluster.routing.IndexShardRoutingTable;
import org.opensearch.cluster.routing.Preference;
import org.opensearch.cluster.routing.ShardRouting;
import org.opensearch.common.settings.Settings;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.indices.replication.common.ReplicationType;
import org.opensearch.remotestore.RemoteStoreBaseIntegTestCase;
import org.opensearch.test.InternalTestCluster;
import org.opensearch.test.OpenSearchIntegTestCase;

import java.io.IOException;

import static org.opensearch.cluster.metadata.IndexMetadata.SETTING_NUMBER_OF_SEARCH_REPLICAS;
import static org.opensearch.cluster.metadata.IndexMetadata.SETTING_REPLICATION_TYPE;
import static org.opensearch.cluster.routing.OperationRouting.STRICT_SEARCH_REPLICA_ROUTING_ENABLED;
import static org.opensearch.cluster.routing.UnassignedInfo.INDEX_DELAYED_NODE_LEFT_TIMEOUT_SETTING;
import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertHitCount;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.core.IsEqual.equalTo;

@OpenSearchIntegTestCase.ClusterScope(scope = OpenSearchIntegTestCase.Scope.TEST, numDataNodes = 0)
public class SearchOnlyReplicaIT extends RemoteStoreBaseIntegTestCase {

    private static final String TEST_INDEX = "test_index";

    @Override
    public Settings indexSettings() {
        return Settings.builder()
            .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0)
            .put(IndexMetadata.SETTING_NUMBER_OF_SEARCH_REPLICAS, 1)
            .put(INDEX_DELAYED_NODE_LEFT_TIMEOUT_SETTING.getKey(), "0ms") // so that after we punt a node we can immediately try to
                                                                          // reallocate after node left.
            .put(SETTING_REPLICATION_TYPE, ReplicationType.SEGMENT)
            .build();
    }

    public void testFailoverWithSearchReplica_WithWriterReplicas() throws IOException {
        int numSearchReplicas = 1;
        int numWriterReplicas = 1;
        internalCluster().startClusterManagerOnlyNode();
        String primaryNodeName = internalCluster().startDataOnlyNode();
        createIndex(
            TEST_INDEX,
            Settings.builder()
                .put(indexSettings())
                .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, numWriterReplicas)
                .put(IndexMetadata.SETTING_NUMBER_OF_SEARCH_REPLICAS, numSearchReplicas)
                .build()
        );
        ensureYellow(TEST_INDEX);
        // add 2 nodes for the replicas
        internalCluster().startDataOnlyNode();
        internalCluster().startSearchOnlyNode();

        ensureGreen(TEST_INDEX);

        // assert shards are on separate nodes & all active
        assertActiveShardCounts(numSearchReplicas, numWriterReplicas);

        // stop the primary and ensure search shard is not promoted:
        internalCluster().stopRandomNode(InternalTestCluster.nameFilter(primaryNodeName));
        ensureYellowAndNoInitializingShards(TEST_INDEX);

        assertActiveShardCounts(numSearchReplicas, 0); // 1 repl is inactive that was promoted to primary
        // add back a node
        internalCluster().startDataOnlyNode();
        ensureGreen(TEST_INDEX);
    }

    public void testFailoverWithSearchReplica_WithoutWriterReplicas() throws IOException {
        int numSearchReplicas = 1;
        int numWriterReplicas = 0;
        internalCluster().startClusterManagerOnlyNode();
        String primaryNodeName = internalCluster().startDataOnlyNode();
        createIndex(
            TEST_INDEX,
            Settings.builder()
                .put(indexSettings())
                .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, numWriterReplicas)
                .put(IndexMetadata.SETTING_NUMBER_OF_SEARCH_REPLICAS, numSearchReplicas)
                .put("index.refresh_interval", "40ms") // set lower interval so replica attempts replication cycles after primary is
                                                       // removed.
                .build()
        );
        ensureYellow(TEST_INDEX);
        client().prepareIndex(TEST_INDEX).setId("1").setSource("foo", "bar").setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE).get();
        // start a node for our search replica
        final String searchNode = internalCluster().startSearchOnlyNode();

        ensureGreen(TEST_INDEX);
        assertActiveSearchShards(numSearchReplicas);
        assertHitCount(client(searchNode).prepareSearch(TEST_INDEX).setSize(0).setPreference("_only_local").get(), 1);

        // stop the primary and ensure search shard is not promoted:
        internalCluster().stopRandomNode(InternalTestCluster.nameFilter(primaryNodeName));
        ensureRed(TEST_INDEX);
        assertActiveSearchShards(numSearchReplicas);
        // while red our search shard is still searchable
        assertHitCount(client(searchNode).prepareSearch(TEST_INDEX).setSize(0).setPreference("_only_local").get(), 1);
    }

    public void testFailoverWithSearchReplicaWhenNodeLeavesCluster() throws IOException {
        internalCluster().startClusterManagerOnlyNode();
        internalCluster().startDataOnlyNode();
        createIndex(TEST_INDEX);
        indexSingleDoc(TEST_INDEX, true);
        ensureYellow(TEST_INDEX);
        // add another node for the search replica
        String searchNode = internalCluster().startSearchOnlyNode();
        ensureGreen(TEST_INDEX);

        // Stop Node which hosts the search replica
        internalCluster().stopRandomNode(InternalTestCluster.nameFilter(searchNode));
        // Ensure search shard is unassigned
        ensureYellowAndNoInitializingShards(TEST_INDEX);
        assertActiveSearchShards(0);

        // Add a node and ensure search shard will get assigned
        internalCluster().startSearchOnlyNode();

        // Ensure search shard is recovered
        ensureGreen(TEST_INDEX);
        assertActiveSearchShards(1);
    }

    public void testFailoverWithSearchReplicaWhenSearchNodeRestarts() throws Exception {
        internalCluster().startClusterManagerOnlyNode();
        internalCluster().startDataOnlyNode();
        createIndex(TEST_INDEX);
        indexSingleDoc(TEST_INDEX, true);
        ensureYellow(TEST_INDEX);
        // add another node for the search replica
        String searchNode = internalCluster().startSearchOnlyNode();
        ensureGreen(TEST_INDEX);
        // Restart Search Node
        internalCluster().restartNode(searchNode, new InternalTestCluster.RestartCallback() {
            @Override
            public Settings onNodeStopped(String nodeName) throws Exception {
                // Ensure search shard is unassigned
                ensureYellowAndNoInitializingShards(TEST_INDEX);
                assertActiveSearchShards(0);
                return super.onNodeStopped(nodeName);
            }
        });
        // Ensure search shard is recovered
        ensureGreen(TEST_INDEX);
        assertActiveSearchShards(1);
    }

    public void testSearchReplicaScaling() {
        internalCluster().startClusterManagerOnlyNode();
        internalCluster().startSearchOnlyNode();
        internalCluster().startDataOnlyNode();

        createIndex(TEST_INDEX);
        ensureGreen(TEST_INDEX);
        // assert settings
        Metadata metadata = client().admin().cluster().prepareState().get().getState().metadata();
        int numSearchReplicas = Integer.parseInt(metadata.index(TEST_INDEX).getSettings().get(SETTING_NUMBER_OF_SEARCH_REPLICAS));
        assertEquals(1, numSearchReplicas);

        // assert cluster state & routing table
        assertActiveSearchShards(1);

        // Add another search node and search replica
        internalCluster().startSearchOnlyNode();

        client().admin()
            .indices()
            .prepareUpdateSettings(TEST_INDEX)
            .setSettings(Settings.builder().put(SETTING_NUMBER_OF_SEARCH_REPLICAS, 2))
            .get();

        ensureGreen(TEST_INDEX);
        assertActiveSearchShards(2);

        // remove all search shards
        client().admin()
            .indices()
            .prepareUpdateSettings(TEST_INDEX)
            .setSettings(Settings.builder().put(SETTING_NUMBER_OF_SEARCH_REPLICAS, 0))
            .get();
        ensureGreen(TEST_INDEX);
        assertActiveSearchShards(0);
    }

    public void testSearchReplicaRoutingPreference() throws IOException {
        int numSearchReplicas = 1;
        int numWriterReplicas = 1;
        internalCluster().startClusterManagerOnlyNode();
        internalCluster().startDataOnlyNode();
        createIndex(
            TEST_INDEX,
            Settings.builder()
                .put(indexSettings())
                .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, numWriterReplicas)
                .put(IndexMetadata.SETTING_NUMBER_OF_SEARCH_REPLICAS, numSearchReplicas)
                .build()
        );
        ensureYellow(TEST_INDEX);
        client().prepareIndex(TEST_INDEX).setId("1").setSource("foo", "bar").setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE).get();
        // add 2 nodes for the replicas
        internalCluster().startDataOnlyNode();
        internalCluster().startSearchOnlyNode();

        ensureGreen(TEST_INDEX);

        assertActiveShardCounts(numSearchReplicas, numWriterReplicas);

        // set preference to search replica here - we default to this when there are
        // search replicas but tests will randomize this value if unset
        SearchResponse response = client().prepareSearch(TEST_INDEX)
            .setPreference(Preference.SEARCH_REPLICA.type())
            .setQuery(QueryBuilders.matchAllQuery())
            .get();

        String nodeId = response.getHits().getAt(0).getShard().getNodeId();
        IndexShardRoutingTable indexShardRoutingTable = getIndexShardRoutingTable();
        assertEquals(nodeId, indexShardRoutingTable.searchOnlyReplicas().get(0).currentNodeId());
    }

    public void testSearchReplicaRoutingPreferenceWhenSearchReplicaUnassigned() {
        internalCluster().startClusterManagerOnlyNode();
        internalCluster().startDataOnlyNode();
        createIndex(TEST_INDEX, Settings.builder().put(indexSettings()).put(IndexMetadata.SETTING_NUMBER_OF_SEARCH_REPLICAS, 1).build());
        ensureYellow(TEST_INDEX);
        client().prepareIndex(TEST_INDEX).setId("1").setSource("foo", "bar").setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE).get();

        // By default cluster.routing.search_replica.strict is set as true
        // When cluster.routing.search_replica.strict is set as true, and no assigned search replica is available,
        // search request will fail since it will route only to search replica but it's not available
        Throwable throwable = assertThrows(
            SearchPhaseExecutionException.class,
            () -> client().prepareSearch(TEST_INDEX).setPreference(null).setQuery(QueryBuilders.matchAllQuery()).get()
        );

        assertEquals("all shards failed", throwable.getMessage());

        // Set cluster.routing.search_replica.strict as false
        client().admin()
            .cluster()
            .prepareUpdateSettings()
            .setTransientSettings(Settings.builder().put(STRICT_SEARCH_REPLICA_ROUTING_ENABLED.getKey(), false))
            .get();

        // When cluster.routing.search_replica.strict is set as false, and no assigned search replica is available;
        // search request will fall back to querying writers
        SearchResponse response = client().prepareSearch(TEST_INDEX).setPreference(null).setQuery(QueryBuilders.matchAllQuery()).get();

        String nodeId = response.getHits().getAt(0).getShard().getNodeId();
        IndexShardRoutingTable indexShardRoutingTable = getIndexShardRoutingTable();
        assertEquals(nodeId, indexShardRoutingTable.primaryShard().currentNodeId());
    }

    public void testSearchReplicaRoutingPreferenceWhenSearchReplicaAssigned() {
        internalCluster().startClusterManagerOnlyNode();
        internalCluster().startDataOnlyNode();
        createIndex(TEST_INDEX, Settings.builder().put(indexSettings()).put(IndexMetadata.SETTING_NUMBER_OF_SEARCH_REPLICAS, 1).build());
        ensureYellow(TEST_INDEX);
        client().prepareIndex(TEST_INDEX).setId("1").setSource("foo", "bar").setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE).get();

        internalCluster().startSearchOnlyNode();
        ensureGreen(TEST_INDEX);

        // By default cluster.routing.search_replica.strict is set as true
        // When cluster.routing.search_replica.strict is set as true, and assigned search replica is available;
        // search request will succeed
        SearchResponse response = client().prepareSearch(TEST_INDEX).setPreference(null).setQuery(QueryBuilders.matchAllQuery()).get();

        String nodeId = response.getHits().getAt(0).getShard().getNodeId();
        IndexShardRoutingTable indexShardRoutingTable = getIndexShardRoutingTable();
        assertEquals(nodeId, indexShardRoutingTable.searchOnlyReplicas().get(0).currentNodeId());

        // Set cluster.routing.search_replica.strict as false
        client().admin()
            .cluster()
            .prepareUpdateSettings()
            .setTransientSettings(Settings.builder().put(STRICT_SEARCH_REPLICA_ROUTING_ENABLED.getKey(), false))
            .get();

        // When cluster.routing.search_replica.strict is set as false, and assigned search replica is available;
        // search request can land on either writer or reader
        response = client().prepareSearch(TEST_INDEX).setPreference(null).setQuery(QueryBuilders.matchAllQuery()).get();

        nodeId = response.getHits().getAt(0).getShard().getNodeId();
        indexShardRoutingTable = getIndexShardRoutingTable();
        assertThat(
            nodeId,
            anyOf(
                equalTo(indexShardRoutingTable.primaryShard().currentNodeId()),
                equalTo(indexShardRoutingTable.searchOnlyReplicas().get(0).currentNodeId())
            )
        );
    }

    public void testUnableToAllocateSearchReplicaWontBlockRegularReplicaAllocation() {
        int numSearchReplicas = 1;
        int numWriterReplicas = 1;
        internalCluster().startClusterManagerOnlyNode();
        internalCluster().startDataOnlyNodes(3);

        createIndex(
            TEST_INDEX,
            Settings.builder()
                .put(indexSettings())
                .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, numWriterReplicas)
                .put(IndexMetadata.SETTING_NUMBER_OF_SEARCH_REPLICAS, numSearchReplicas)
                .build()
        );

        ensureYellowAndNoInitializingShards(TEST_INDEX);
        assertActiveShardCounts(0, numWriterReplicas);
    }

    public void testUnableToAllocateRegularReplicaWontBlockSearchReplicaAllocation() {
        int numSearchReplicas = 1;
        int numWriterReplicas = 1;
        internalCluster().startClusterManagerOnlyNode();
        internalCluster().startDataOnlyNode();
        internalCluster().startSearchOnlyNode();

        createIndex(
            TEST_INDEX,
            Settings.builder()
                .put(indexSettings())
                .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, numWriterReplicas)
                .put(IndexMetadata.SETTING_NUMBER_OF_SEARCH_REPLICAS, numSearchReplicas)
                .build()
        );
        ensureYellowAndNoInitializingShards(TEST_INDEX);
        assertActiveShardCounts(numSearchReplicas, 0);
    }

    /**
     * Helper to assert counts of active shards for each type.
     */
    private void assertActiveShardCounts(int expectedSearchReplicaCount, int expectedWriteReplicaCount) {
        // assert routing table
        IndexShardRoutingTable indexShardRoutingTable = getIndexShardRoutingTable();
        // assert search replica count
        int activeCount = expectedSearchReplicaCount + expectedWriteReplicaCount;
        assertEquals(expectedSearchReplicaCount, indexShardRoutingTable.searchOnlyReplicas().stream().filter(ShardRouting::active).count());
        assertEquals(expectedWriteReplicaCount, indexShardRoutingTable.writerReplicas().stream().filter(ShardRouting::active).count());
        assertEquals(
            expectedWriteReplicaCount + expectedSearchReplicaCount,
            indexShardRoutingTable.replicaShards().stream().filter(ShardRouting::active).count()
        );

        // assert routing nodes
        ClusterState clusterState = getClusterState();
        assertEquals(activeCount, clusterState.getRoutingNodes().shards(r -> r.active() && !r.primary()).size());
        assertEquals(expectedSearchReplicaCount, clusterState.getRoutingNodes().shards(r -> r.active() && r.isSearchOnly()).size());
        assertEquals(
            expectedWriteReplicaCount,
            clusterState.getRoutingNodes().shards(r -> r.active() && !r.primary() && !r.isSearchOnly()).size()
        );
    }

    private void assertActiveSearchShards(int expectedSearchReplicaCount) {
        assertActiveShardCounts(expectedSearchReplicaCount, 0);
    }

    private IndexShardRoutingTable getIndexShardRoutingTable() {
        return getClusterState().routingTable().index(TEST_INDEX).shards().values().stream().findFirst().get();
    }
}
