/**
 * Copyright (c) 2002-2014 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
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
package org.neo4j.io.pagecache.impl.muninn;

import java.io.IOException;

import org.neo4j.collection.primitive.PrimitiveLongObjectVisitor;
import org.neo4j.io.pagecache.PageSwapper;
import org.neo4j.io.pagecache.monitoring.FlushEventOpportunity;
import org.neo4j.io.pagecache.monitoring.MajorFlushEvent;

final class PageFlusher implements PrimitiveLongObjectVisitor<MuninnPage, IOException>
{
    private final PageSwapper swapper;
    private final FlushEventOpportunity flushOpportunity;

    public PageFlusher( PageSwapper swapper, MajorFlushEvent flushEvent )
    {
        this.swapper = swapper;
        this.flushOpportunity = flushEvent.flushEventOpportunity();
    }

    @Override
    public boolean visited( long filePageId, MuninnPage page ) throws IOException
    {
        long stamp = page.readLock();
        try
        {
            page.flush( swapper, filePageId, flushOpportunity );
            return false;
        }
        finally
        {
            page.unlockRead( stamp );
        }
    }
}
