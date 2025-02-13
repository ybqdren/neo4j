/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.core;

import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.neo4j.internal.helpers.NamedThreadFactory.named;
import static org.neo4j.internal.helpers.collection.Iterables.count;
import static org.neo4j.test.DoubleLatch.awaitLatch;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.exceptions.KernelException;
import org.neo4j.graphdb.ConstraintViolationException;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.Entity;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.NotFoundException;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.ResourceIterable;
import org.neo4j.graphdb.Transaction;
import org.neo4j.internal.helpers.collection.Iterables;
import org.neo4j.internal.helpers.collection.Iterators;
import org.neo4j.internal.kernel.api.TokenRead;
import org.neo4j.internal.kernel.api.exceptions.EntityNotFoundException;
import org.neo4j.io.pagecache.context.CursorContext;
import org.neo4j.io.pagecache.tracing.cursor.DefaultPageCursorTracer;
import org.neo4j.io.pagecache.tracing.cursor.PageCursorTracer;
import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.kernel.impl.coreapi.InternalTransaction;

public class NodeEntityTest extends EntityTest {
    private final String PROPERTY_KEY = "PROPERTY_KEY";

    @Override
    protected long createEntity(Transaction tx) {
        return tx.createNode().getId();
    }

    @Override
    protected Entity lookupEntity(Transaction transaction, long id) {
        return transaction.getNodeById(id);
    }

    @Test
    void traceNodePageCacheAccessOnDegreeCount() {
        long sourceId;
        try (Transaction tx = db.beginTx()) {
            var source = tx.createNode();
            var relationshipType = RelationshipType.withName("connection");
            createDenseNodeWithShortIncomingChain(tx, source, relationshipType);
            sourceId = source.getId();
            tx.commit();
        }

        try (Transaction tx = db.beginTx()) {
            var cursorContext = ((InternalTransaction) tx).kernelTransaction().cursorContext();
            PageCursorTracer cursorTracer = cursorContext.getCursorTracer();
            var source = tx.getNodeById(sourceId);
            ((DefaultPageCursorTracer) cursorTracer).setIgnoreCounterCheck(true);
            cursorTracer.reportEvents();
            assertZeroTracer(cursorContext);

            source.getDegree(Direction.INCOMING);

            assertThat(cursorTracer.hits()).isEqualTo(3);
            assertThat(cursorTracer.unpins()).isEqualTo(0);
            assertThat(cursorTracer.pins()).isEqualTo(3);
        }
    }

    @Test
    void traceNodePageCacheAccessOnRelationshipTypeAndDegreeCount() {
        long sourceId;
        var relationshipType = RelationshipType.withName("connection");
        try (Transaction tx = db.beginTx()) {
            var source = tx.createNode();
            createDenseNodeWithShortIncomingChain(tx, source, relationshipType);
            sourceId = source.getId();
            tx.commit();
        }

        try (Transaction tx = db.beginTx()) {
            var cursorContext = ((InternalTransaction) tx).kernelTransaction().cursorContext();
            PageCursorTracer cursorTracer = cursorContext.getCursorTracer();
            var source = tx.getNodeById(sourceId);
            ((DefaultPageCursorTracer) cursorTracer).setIgnoreCounterCheck(true);
            cursorTracer.reportEvents();
            assertZeroTracer(cursorContext);

            source.getDegree(relationshipType, Direction.INCOMING);

            assertThat(cursorTracer.hits()).isEqualTo(3);
            assertThat(cursorTracer.unpins()).isEqualTo(0);
            assertThat(cursorTracer.pins()).isEqualTo(3);
        }
    }

    @Test
    void traceNodePageCacheAccessOnRelationshipsAccess() {
        long targetId;
        var relationshipType = RelationshipType.withName("connection");
        try (Transaction tx = db.beginTx()) {
            var target = tx.createNode();
            for (int i = 0; i < 100; i++) {
                tx.createNode().createRelationshipTo(target, relationshipType);
            }
            targetId = target.getId();
            tx.commit();
        }

        try (Transaction tx = db.beginTx()) {
            var cursorContext = ((InternalTransaction) tx).kernelTransaction().cursorContext();
            PageCursorTracer cursorTracer = cursorContext.getCursorTracer();
            var source = tx.getNodeById(targetId);
            ((DefaultPageCursorTracer) cursorTracer).setIgnoreCounterCheck(true);
            cursorTracer.reportEvents();
            assertZeroTracer(cursorContext);

            assertThat(count(source.getRelationships(Direction.INCOMING, relationshipType)))
                    .isGreaterThan(0);

            assertThat(cursorTracer.hits()).isEqualTo(3);
            assertThat(cursorTracer.pins()).isEqualTo(3);
        }
    }

    @Test
    void shouldThrowHumaneExceptionsWhenPropertyDoesNotExistOnNode() {
        // Given a database with PROPERTY_KEY in it
        createNodeWith(PROPERTY_KEY);

        // When trying to get property from node without it
        NotFoundException exception = assertThrows(NotFoundException.class, () -> {
            try (Transaction tx = db.beginTx()) {
                Node node = tx.createNode();
                node.getProperty(PROPERTY_KEY);
            }
        });
        assertThat(exception.getMessage()).contains(PROPERTY_KEY);
    }

    @Test
    void createDropNodeLongStringProperty() {
        Label markerLabel = Label.label("marker");
        String testPropertyKey = "testProperty";
        String propertyValue = RandomStringUtils.randomAscii(255);

        try (Transaction tx = db.beginTx()) {
            Node node = tx.createNode(markerLabel);
            node.setProperty(testPropertyKey, propertyValue);
            tx.commit();
        }

        try (Transaction tx = db.beginTx()) {
            Node node = Iterators.single(tx.findNodes(markerLabel));
            assertEquals(propertyValue, node.getProperty(testPropertyKey));
            tx.commit();
        }

        try (Transaction tx = db.beginTx()) {
            Node node = Iterators.single(tx.findNodes(markerLabel));
            node.removeProperty(testPropertyKey);
            tx.commit();
        }

        try (Transaction tx = db.beginTx()) {
            Node node = Iterators.single(tx.findNodes(markerLabel));
            assertFalse(node.hasProperty(testPropertyKey));
            tx.commit();
        }
    }

    @Test
    void createDropNodeLongArrayProperty() {
        Label markerLabel = Label.label("marker");
        String testPropertyKey = "testProperty";
        byte[] propertyValue = RandomUtils.nextBytes(1024);

        try (Transaction tx = db.beginTx()) {
            Node node = tx.createNode(markerLabel);
            node.setProperty(testPropertyKey, propertyValue);
            tx.commit();
        }

        try (Transaction tx = db.beginTx()) {
            Node node = Iterators.single(tx.findNodes(markerLabel));
            assertArrayEquals(propertyValue, (byte[]) node.getProperty(testPropertyKey));
            tx.commit();
        }

        try (Transaction tx = db.beginTx()) {
            Node node = Iterators.single(tx.findNodes(markerLabel));
            node.removeProperty(testPropertyKey);
            tx.commit();
        }

        try (Transaction tx = db.beginTx()) {
            Node node = Iterators.single(tx.findNodes(markerLabel));
            assertFalse(node.hasProperty(testPropertyKey));
            tx.commit();
        }
    }

    @Test
    void shouldThrowHumaneExceptionsWhenPropertyDoesNotExist() {
        // Given a database without PROPERTY_KEY in it

        // When
        try (Transaction tx = db.beginTx()) {
            Node node = tx.createNode();
            node.getProperty(PROPERTY_KEY);
        }
        // Then
        catch (NotFoundException exception) {
            assertThat(exception.getMessage()).contains(PROPERTY_KEY);
        }
    }

    @Test
    void deletionOfSameNodeTwiceInOneTransactionShouldNotRollbackIt() {
        // Given
        Node node;
        try (Transaction tx = db.beginTx()) {
            node = tx.createNode();
            tx.commit();
        }

        // When
        Exception exceptionThrownBySecondDelete = null;

        try (Transaction tx = db.beginTx()) {
            tx.getNodeById(node.getId()).delete();
            try {
                tx.getNodeById(node.getId()).delete();
            } catch (Exception e) {
                exceptionThrownBySecondDelete = e;
            }
            tx.commit();
        }

        // Then
        assertThat(exceptionThrownBySecondDelete).isInstanceOf(NotFoundException.class);

        assertThrows(NotFoundException.class, () -> {
            try (Transaction tx = db.beginTx()) {
                tx.getNodeById(node.getId()); // should throw NotFoundException
                tx.commit();
            }
        });
    }

    @Test
    void deletionOfAlreadyDeletedNodeShouldThrow() {
        // Given
        Node node;
        try (Transaction tx = db.beginTx()) {
            node = tx.createNode();
            tx.commit();
        }
        try (Transaction tx = db.beginTx()) {
            tx.getNodeById(node.getId()).delete();
            tx.commit();
        }

        // When
        assertThrows(NotFoundException.class, () -> {
            try (Transaction tx = db.beginTx()) {
                tx.getNodeById(node.getId()).delete(); // should throw NotFoundException as this node is already deleted
                tx.commit();
            }
        });
    }

    @Test
    void getAllPropertiesShouldWorkFineWithConcurrentPropertyModifications() throws Exception {
        // Given
        ExecutorService executor = Executors.newFixedThreadPool(2, named("Test-executor-thread"));
        try {
            final int propertiesCount = 100;

            final long nodeId;
            try (Transaction tx = db.beginTx()) {
                Node node = tx.createNode();
                nodeId = node.getId();
                for (int i = 0; i < propertiesCount; i++) {
                    node.setProperty("property-" + i, i);
                }
                tx.commit();
            }

            final CountDownLatch start = new CountDownLatch(1);
            final AtomicBoolean writerDone = new AtomicBoolean();

            Runnable writer = () -> {
                try {
                    awaitLatch(start);
                    int propertyKey = 0;
                    while (propertyKey < propertiesCount) {
                        try (Transaction tx = db.beginTx()) {
                            Node node = tx.getNodeById(nodeId);
                            for (int i = 0; i < 10 && propertyKey < propertiesCount; i++, propertyKey++) {
                                node.setProperty(
                                        "property-" + propertyKey,
                                        UUID.randomUUID().toString());
                            }
                            tx.commit();
                        }
                    }
                } finally {
                    writerDone.set(true);
                }
            };
            Runnable reader = () -> {
                try (Transaction tx = db.beginTx()) {
                    Node node = tx.getNodeById(nodeId);
                    awaitLatch(start);
                    while (!writerDone.get()) {
                        int size = node.getAllProperties().size();
                        assertThat(size).isGreaterThan(0);
                    }
                    tx.commit();
                }
            };

            Future<?> readerFuture = executor.submit(reader);
            Future<?> writerFuture = executor.submit(writer);

            start.countDown();

            // When
            writerFuture.get();
            readerFuture.get();

            // Then
            try (Transaction tx = db.beginTx()) {
                assertEquals(
                        propertiesCount,
                        tx.getNodeById(nodeId).getAllProperties().size());
                tx.commit();
            }
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void shouldBeAbleToForceTypeChangeOfProperty() {
        // Given
        Node node;
        try (Transaction tx = db.beginTx()) {
            node = tx.createNode();
            node.setProperty("prop", 1337);
            tx.commit();
        }

        // When
        try (Transaction tx = db.beginTx()) {
            tx.getNodeById(node.getId()).setProperty("prop", 1337.0);
            tx.commit();
        }

        // Then
        try (Transaction tx = db.beginTx()) {
            assertThat(tx.getNodeById(node.getId()).getProperty("prop")).isInstanceOf(Double.class);
        }
    }

    @Test
    void shouldOnlyReturnTypeOnce() {
        // Given
        Node node;
        try (Transaction tx = db.beginTx()) {
            node = tx.createNode();
            node.createRelationshipTo(tx.createNode(), RelationshipType.withName("R"));
            node.createRelationshipTo(tx.createNode(), RelationshipType.withName("R"));
            node.createRelationshipTo(tx.createNode(), RelationshipType.withName("R"));
            tx.commit();
        }

        // Then
        try (Transaction tx = db.beginTx()) {
            assertThat(Iterables.asList(tx.getNodeById(node.getId()).getRelationshipTypes()))
                    .isEqualTo(singletonList(RelationshipType.withName("R")));
        }
    }

    @Disabled(
            "Tracking of iterables from getRelationships have been (temporarily) disabled due to performance regressions")
    @Test
    void getRelationshipsCallsShouldRegisterAndUnregisterAsResource() {
        verifyGetRelationshipsCalls(NodeEntity::getRelationships);
    }

    @Disabled(
            "Tracking of iterables from getRelationships have been (temporarily) disabled due to performance regressions")
    @Test
    void getRelationshipsWithDirectionCallsShouldRegisterAndUnregisterAsResource() {
        verifyGetRelationshipsCalls(node -> node.getRelationships(Direction.INCOMING));
    }

    @Disabled(
            "Tracking of iterables from getRelationships have been (temporarily) disabled due to performance regressions")
    @Test
    void getRelationshipsWithTypeCallsShouldRegisterAndUnregisterAsResource() {
        verifyGetRelationshipsCalls(node -> node.getRelationships(RelationshipType.withName("R")));
    }

    @Test
    void shouldThrowCorrectExceptionOnLabelTokensExceeded() throws KernelException {
        // given
        var transaction = mockedTransactionWithDepletedTokens();
        NodeEntity nodeEntity = new NodeEntity(transaction, 5);

        // when
        assertThrows(ConstraintViolationException.class, () -> nodeEntity.addLabel(Label.label("Label")));
    }

    @Test
    void shouldThrowCorrectExceptionOnPropertyKeyTokensExceeded() throws KernelException {
        // given
        NodeEntity nodeEntity = new NodeEntity(mockedTransactionWithDepletedTokens(), 5);

        // when
        assertThrows(ConstraintViolationException.class, () -> nodeEntity.setProperty("key", "value"));
    }

    @Test
    void shouldThrowCorrectExceptionOnRelationshipTypeTokensExceeded() throws KernelException {
        // given
        NodeEntity nodeEntity = new NodeEntity(mockedTransactionWithDepletedTokens(), 5);

        // when
        assertThrows(ConstraintViolationException.class, () -> nodeEntity.setProperty("key", "value"));
    }

    @Test
    void shouldWorkWithNodeElementIds() {
        // given
        String nodeId1;
        String nodeId2;
        try (Transaction tx = db.beginTx()) {
            var node1 = tx.createNode();
            node1.setProperty("name", "Node 1");
            nodeId1 = node1.getElementId();
            var node2 = tx.createNode();
            node2.setProperty("name", "Node 2");
            nodeId2 = node2.getElementId();
            tx.commit();
        }

        // when/then
        try (Transaction tx = db.beginTx()) {
            var node1 = tx.getNodeByElementId(nodeId1);
            var node2 = tx.getNodeByElementId(nodeId2);
            assertThat(node1.getProperty("name")).isEqualTo("Node 1");
            assertThat(node2.getProperty("name")).isEqualTo("Node 2");
            tx.commit();
        }
    }

    @Test
    void nodeNotFoundByElementId() {
        String elementId;
        try (Transaction transaction = db.beginTx()) {
            Node node = transaction.createNode();
            elementId = node.getElementId();
            transaction.rollback();
        }
        assertThatThrownBy(() -> {
                    try (Transaction tx = db.beginTx()) {
                        tx.getNodeByElementId(elementId);
                    }
                })
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(elementId + " not found")
                .hasRootCauseInstanceOf(EntityNotFoundException.class)
                .getRootCause()
                .hasMessageContaining("Unable to load NODE " + elementId);
    }

    @Test
    void shouldWorkWithRelationshipElementIds() {
        // given
        String relationshipId1;
        String relationshipId2;
        try (Transaction tx = db.beginTx()) {
            var relationship1 =
                    tx.createNode().createRelationshipTo(tx.createNode(), RelationshipType.withName("KNOWS"));
            relationship1.setProperty("name", "Relationship 1");
            relationshipId1 = relationship1.getElementId();
            var relationship2 =
                    tx.createNode().createRelationshipTo(tx.createNode(), RelationshipType.withName("KNOWS"));
            relationship2.setProperty("name", "Relationship 2");
            relationshipId2 = relationship2.getElementId();
            tx.commit();
        }

        // when/then
        try (Transaction tx = db.beginTx()) {
            var relationship1 = tx.getRelationshipByElementId(relationshipId1);
            var relationship2 = tx.getRelationshipByElementId(relationshipId2);
            assertThat(relationship1.getProperty("name")).isEqualTo("Relationship 1");
            assertThat(relationship2.getProperty("name")).isEqualTo("Relationship 2");
            tx.commit();
        }
    }

    private void createNodeWith(String key) {
        try (Transaction tx = db.beginTx()) {
            Node node = tx.createNode();
            node.setProperty(key, 1);
            tx.commit();
        }
    }

    private void verifyGetRelationshipsCalls(Function<NodeEntity, ResourceIterable<Relationship>> provider) {
        TokenRead tokenRead = mock(TokenRead.class);
        when(tokenRead.relationshipType(anyString())).thenReturn(13);

        var kernelTransaction = mock(KernelTransaction.class);
        when(kernelTransaction.tokenRead()).thenReturn(tokenRead);

        var internalTransaction = mock(InternalTransaction.class);
        when(internalTransaction.kernelTransaction()).thenReturn(kernelTransaction);

        ResourceIterable<Relationship> nodes = provider.apply(new NodeEntity(internalTransaction, 42));
        verify(internalTransaction, times(1)).registerCloseableResource(eq(nodes));
        verify(internalTransaction, never()).unregisterCloseableResource(any());

        nodes.close();
        verify(internalTransaction, times(1)).registerCloseableResource(eq(nodes));
        verify(internalTransaction, times(1)).unregisterCloseableResource(eq(nodes));
    }

    private static void assertZeroTracer(CursorContext cursorContext) {
        PageCursorTracer cursorTracer = cursorContext.getCursorTracer();
        assertThat(cursorTracer.hits()).isZero();
        assertThat(cursorTracer.unpins()).isZero();
        assertThat(cursorTracer.pins()).isZero();
    }

    private static void createDenseNodeWithShortIncomingChain(
            Transaction tx, Node source, RelationshipType relationshipType) {
        // This test measures page cache access very specifically when accessing degree for dense node.
        // For dense nodes chain degrees gets "upgraded" to live in a separate degrees store on a certain chain length
        // threshold
        // which is why we create an additional short chain where this still is the case
        for (int i = 0; i < GraphDatabaseSettings.dense_node_threshold.defaultValue() * 2; i++) {
            source.createRelationshipTo(tx.createNode(), relationshipType);
        }
        tx.createNode().createRelationshipTo(source, relationshipType);
    }
}
