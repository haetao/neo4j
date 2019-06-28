/*
 * Copyright (c) 2002-2019 "Neo4j,"
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
package org.neo4j.kernel.impl.newapi;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.TimeoutException;

import org.neo4j.common.EntityType;
import org.neo4j.exceptions.KernelException;
import org.neo4j.function.Predicates;
import org.neo4j.graphdb.Label;
import org.neo4j.internal.kernel.api.IndexOrder;
import org.neo4j.internal.kernel.api.IndexQuery;
import org.neo4j.internal.kernel.api.IndexReadSession;
import org.neo4j.internal.kernel.api.IndexReference;
import org.neo4j.internal.kernel.api.NodeCursor;
import org.neo4j.internal.kernel.api.NodeLabelIndexCursor;
import org.neo4j.internal.kernel.api.NodeValueIndexCursor;
import org.neo4j.internal.kernel.api.PropertyCursor;
import org.neo4j.internal.kernel.api.RelationshipGroupCursor;
import org.neo4j.internal.kernel.api.RelationshipIndexCursor;
import org.neo4j.internal.kernel.api.RelationshipScanCursor;
import org.neo4j.internal.kernel.api.RelationshipTraversalCursor;
import org.neo4j.internal.kernel.api.Transaction;
import org.neo4j.internal.schema.IndexConfig;
import org.neo4j.internal.schema.SchemaDescriptor;
import org.neo4j.values.storable.Values;

import static java.util.concurrent.TimeUnit.MINUTES;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.neo4j.internal.kernel.api.InternalIndexState.ONLINE;
import static org.neo4j.kernel.api.impl.fulltext.FulltextIndexProviderFactory.DESCRIPTOR;
import static org.neo4j.kernel.impl.newapi.TestKernelReadTracer.OnAllNodesScan;
import static org.neo4j.kernel.impl.newapi.TestKernelReadTracer.OnIndexSeek;
import static org.neo4j.kernel.impl.newapi.TestKernelReadTracer.OnLabelScan;
import static org.neo4j.kernel.impl.newapi.TestKernelReadTracer.OnNode;
import static org.neo4j.kernel.impl.newapi.TestKernelReadTracer.OnProperty;
import static org.neo4j.kernel.impl.newapi.TestKernelReadTracer.OnRelationship;
import static org.neo4j.kernel.impl.newapi.TestKernelReadTracer.OnRelationshipGroup;

abstract class KernelReadTracerTxStateTestBase<G extends KernelAPIWriteTestSupport>
        extends KernelAPIWriteTestBase<G>
{
    @Test
    void shouldTraceAllNodesScan() throws Exception
    {
        // given
        TestKernelReadTracer tracer = new TestKernelReadTracer();

        try ( Transaction tx = beginTransaction();
              NodeCursor cursor = tx.cursors().allocateNodeCursor() )
        {
            tx.dataWrite().nodeCreate();
            tx.dataWrite().nodeCreate();

            // when
            cursor.setTracer( tracer );
            tx.dataRead().allNodesScan( cursor );
            tracer.assertEvents( OnAllNodesScan );

            assertTrue( cursor.next() );
            tracer.assertEvents( OnNode( cursor.nodeReference() ) );

            assertTrue( cursor.next() );
            tracer.assertEvents( OnNode( cursor.nodeReference() ) );

            assertFalse( cursor.next() );
            tracer.assertEvents();
        }
    }

    @Test
    void shouldTraceLabelScan() throws KernelException
    {
        // given
        TestKernelReadTracer tracer = new TestKernelReadTracer();

        try ( Transaction tx = beginTransaction();
              NodeLabelIndexCursor cursor = tx.cursors().allocateNodeLabelIndexCursor() )
        {
            int barId = tx.tokenWrite().labelGetOrCreateForName( "Bar" );
            long n = tx.dataWrite().nodeCreate();
            tx.dataWrite().nodeAddLabel( n, barId );

            // when
            cursor.setTracer( tracer );
            tx.dataRead().nodeLabelScan( barId, cursor );
            tracer.assertEvents( OnLabelScan( barId ) );

            assertTrue( cursor.next() );
            tracer.assertEvents( OnNode( cursor.nodeReference() ) );

            assertFalse( cursor.next() );
            tracer.assertEvents();
        }
    }

    @Test
    void shouldTraceIndexSeek() throws KernelException
    {
        // given
        TestKernelReadTracer tracer = new TestKernelReadTracer();

        createIndex( "User", "name" );

        try ( Transaction tx = beginTransaction();
              NodeValueIndexCursor cursor = tx.cursors().allocateNodeValueIndexCursor() )
        {
            int name = tx.token().propertyKey( "name" );
            int user = tx.token().nodeLabel( "User" );
            long n = tx.dataWrite().nodeCreate();
            tx.dataWrite().nodeAddLabel( n, user );
            tx.dataWrite().nodeSetProperty( n, name, Values.stringValue( "Bosse" ) );
            IndexReference index = tx.schemaRead().index( user, name );
            IndexReadSession session = tx.dataRead().indexReadSession( index );

            // when
            assertIndexSeekTracing( tracer, tx, cursor, session, IndexOrder.NONE, false, user );
            assertIndexSeekTracing( tracer, tx, cursor, session, IndexOrder.NONE, true, user );
            assertIndexSeekTracing( tracer, tx, cursor, session, IndexOrder.ASCENDING, false, user );
            assertIndexSeekTracing( tracer, tx, cursor, session, IndexOrder.ASCENDING, true, user );
        }
    }

    private void assertIndexSeekTracing( TestKernelReadTracer tracer,
                                         Transaction tx,
                                         NodeValueIndexCursor cursor,
                                         IndexReadSession session,
                                         IndexOrder order,
                                         boolean needsValues,
                                         int user ) throws KernelException
    {
        cursor.setTracer( tracer );

        tx.dataRead().nodeIndexSeek( session, cursor, order, needsValues, IndexQuery.stringPrefix( user, Values.stringValue( "B" ) ) );
        tracer.assertEvents( OnIndexSeek() );

        assertTrue( cursor.next() );
        tracer.assertEvents( OnNode( cursor.nodeReference() ) );

        assertFalse( cursor.next() );
        tracer.assertEvents();
    }

    @Test
    void shouldTraceSingleRelationship() throws Exception
    {
        // given
        TestKernelReadTracer tracer = new TestKernelReadTracer();

        try ( Transaction tx = beginTransaction();
              RelationshipScanCursor cursor = tx.cursors().allocateRelationshipScanCursor() )
        {
            long n1 = tx.dataWrite().nodeCreate();
            long n2 = tx.dataWrite().nodeCreate();
            long r = tx.dataWrite().relationshipCreate( n1, tx.token().relationshipTypeGetOrCreateForName( "R" ), n2 );

            // when
            cursor.setTracer( tracer );
            tx.dataRead().singleRelationship( r, cursor );

            assertTrue( cursor.next() );
            tracer.assertEvents( OnRelationship( r ) );

            long deleted = tx.dataWrite().relationshipCreate( n1, tx.token().relationshipTypeGetOrCreateForName( "R" ), n2 );
            tx.dataWrite().relationshipDelete( deleted );

            tx.dataRead().singleRelationship( deleted, cursor );
            assertFalse( cursor.next() );
            tracer.assertEvents();
        }
    }

    @Test
    void shouldTraceRelationshipTraversal() throws Exception
    {
        // given
        TestKernelReadTracer tracer = new TestKernelReadTracer();

        try ( Transaction tx = beginTransaction();
              NodeCursor nodeCursor = tx.cursors().allocateNodeCursor();
              RelationshipTraversalCursor cursor = tx.cursors().allocateRelationshipTraversalCursor() )
        {
            long n1 = tx.dataWrite().nodeCreate();
            long n2 = tx.dataWrite().nodeCreate();
            long r = tx.dataWrite().relationshipCreate( n1, tx.token().relationshipTypeGetOrCreateForName( "R" ), n2 );

            // when
            cursor.setTracer( tracer );
            tx.dataRead().singleNode( n1, nodeCursor );
            assertTrue( nodeCursor.next() );
            nodeCursor.allRelationships( cursor );

            assertTrue( cursor.next() );
            tracer.assertEvents( OnRelationship( r ) );

            assertFalse( cursor.next() );
            tracer.assertEvents();
        }
    }

    @Test
    void shouldTraceGroupTraversal() throws Exception
    {
        // given
        TestKernelReadTracer tracer = new TestKernelReadTracer();

        try ( Transaction tx = beginTransaction();
              NodeCursor nodeCursor = tx.cursors().allocateNodeCursor();
              RelationshipGroupCursor cursor = tx.cursors().allocateRelationshipGroupCursor() )
        {
            long n1 = tx.dataWrite().nodeCreate();
            long n2 = tx.dataWrite().nodeCreate();
            tx.dataWrite().relationshipCreate( n1, tx.token().relationshipTypeGetOrCreateForName( "R" ), n2 );

            // when
            cursor.setTracer( tracer );
            tx.dataRead().singleNode( n1, nodeCursor );
            assertTrue( nodeCursor.next() );
            nodeCursor.relationships( cursor );

            assertTrue( cursor.next() );
            int expectedType = cursor.type();
            tracer.assertEvents( OnRelationshipGroup( expectedType ) );

            assertFalse( cursor.next() );
            tracer.assertEvents();
        }
    }

    @Test
    void shouldTracePropertyAccess() throws Exception
    {
        // given
        TestKernelReadTracer tracer = new TestKernelReadTracer();

        try ( Transaction tx = beginTransaction();
              NodeCursor nodeCursor = tx.cursors().allocateNodeCursor();
              PropertyCursor propertyCursor = tx.cursors().allocatePropertyCursor() )
        {
            long n = tx.dataWrite().nodeCreate();
            int name = tx.token().propertyKey( "name" );
            tx.dataWrite().nodeSetProperty( n, name, Values.stringValue( "Bosse" ) );

            // when
            propertyCursor.setTracer( tracer );

            tx.dataRead().singleNode( n, nodeCursor );
            assertTrue( nodeCursor.next() );
            nodeCursor.properties( propertyCursor );

            assertTrue( propertyCursor.next() );
            tracer.assertEvents( OnProperty( name ) );

            assertFalse( propertyCursor.next() );
            tracer.assertEvents();
        }
    }

    @Test
    void shouldTraceRelationshipIndexCursor() throws KernelException, TimeoutException
    {
        // given
        int connection;
        int name;
        String indexName = "myIndex";
        IndexReference index;

        try ( Transaction tx = beginTransaction() )
        {
            connection = tx.tokenWrite().relationshipTypeGetOrCreateForName( "Connection" );
            name = tx.tokenWrite().propertyKeyGetOrCreateForName( "name" );
            tx.success();
        }

        try ( Transaction tx = beginTransaction() )
        {
            SchemaDescriptor schema = SchemaDescriptor.fulltext( EntityType.RELATIONSHIP,
                                                                 IndexConfig.empty(),
                                                                 array( connection ),
                                                                 array( name ) );
            index = tx.schemaWrite().indexCreate( schema, DESCRIPTOR.name(), Optional.of( indexName ) );
            tx.success();
        }

        try ( Transaction tx = beginTransaction() )
        {
            Predicates.awaitEx( () -> tx.schemaRead().indexGetState( index ) == ONLINE, 1, MINUTES );
            long n1 = tx.dataWrite().nodeCreate();
            long n2 = tx.dataWrite().nodeCreate();
            long r = tx.dataWrite().relationshipCreate( n1, connection, n2 );
            tx.dataWrite().relationshipSetProperty( r, name, Values.stringValue( "transformational" ) );
            tx.success();
        }

        // when
        TestKernelReadTracer tracer = new TestKernelReadTracer();

        try ( Transaction tx = beginTransaction();
              RelationshipIndexCursor cursor = tx.cursors().allocateRelationshipIndexCursor() )
        {
            cursor.setTracer( tracer );
            tx.dataRead().relationshipIndexSeek( index, cursor, IndexQuery.fulltextSearch( "transformational" ) );

            assertTrue( cursor.next() );
            tracer.assertEvents( OnRelationship( cursor.relationshipReference() ) );

            assertFalse( cursor.next() );
            tracer.assertEvents();
        }
    }

    private int[] array( int... elements )
    {
        return elements;
    }

    @SuppressWarnings( "SameParameterValue" )
    private void createIndex( String label, String propertyKey )
    {
        try ( org.neo4j.graphdb.Transaction tx = graphDb.beginTx() )
        {
            graphDb.schema().indexFor( Label.label( label ) ).on( propertyKey ).create();
            tx.success();
        }

        try ( org.neo4j.graphdb.Transaction tx = graphDb.beginTx() )
        {
            graphDb.schema().awaitIndexesOnline( 1, MINUTES );
        }
    }
}