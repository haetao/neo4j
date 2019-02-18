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
package org.neo4j.graphdb.facade;

import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.map.ImmutableMap;
import org.eclipse.collections.impl.list.immutable.ImmutableListFactoryImpl;
import org.eclipse.collections.impl.map.immutable.ImmutableMapFactoryImpl;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.neo4j.common.Service;
import org.neo4j.graphdb.security.URLAccessRule;
import org.neo4j.helpers.collection.Pair;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.kernel.extension.ExtensionFactory;
import org.neo4j.kernel.impl.query.QueryEngineProvider;
import org.neo4j.kernel.impl.security.URLAccessRules;
import org.neo4j.kernel.monitoring.Monitors;
import org.neo4j.logging.LogProvider;
import org.neo4j.scheduler.DeferredExecutor;
import org.neo4j.scheduler.Group;

import static org.neo4j.helpers.collection.Iterables.asImmutableList;
import static org.neo4j.helpers.collection.Iterables.asImmutableMap;
import static org.neo4j.helpers.collection.Iterables.asIterable;
import static org.neo4j.helpers.collection.Iterables.concat;

public class GraphDatabaseDependencies implements ExternalDependencies
{
    public static GraphDatabaseDependencies newDependencies( ExternalDependencies deps )
    {
        return new GraphDatabaseDependencies( deps.monitors(), deps.userLogProvider(), deps.pageCache(),
                asImmutableList( deps.settingsClasses() ), asImmutableList( deps.extensions() ),
                asImmutableMap( deps.urlAccessRules() ), asImmutableList( deps.executionEngines() ),
                asImmutableList( deps.deferredExecutors() ) );
    }

    public static GraphDatabaseDependencies newDependencies()
    {
        ImmutableList<Class<?>> settingsClasses = ImmutableListFactoryImpl.INSTANCE.empty();
        ImmutableList<ExtensionFactory<?>> extensions = asImmutableList(
                getExtensions( Service.loadAll( ExtensionFactory.class ).iterator() ) );

        ImmutableMap<String,URLAccessRule> urlAccessRules = ImmutableMapFactoryImpl.INSTANCE.of(
                "http", URLAccessRules.alwaysPermitted(),
                "https", URLAccessRules.alwaysPermitted(),
                "ftp", URLAccessRules.alwaysPermitted(),
                "file", URLAccessRules.fileAccess()
        );

        ImmutableList<QueryEngineProvider> queryEngineProviders = asImmutableList( Service.loadAll( QueryEngineProvider.class ) );
        ImmutableList<Pair<DeferredExecutor,Group>> deferredExecutors = ImmutableListFactoryImpl.INSTANCE.empty();

        return new GraphDatabaseDependencies( null, null, null, settingsClasses, extensions,
                urlAccessRules, queryEngineProviders, deferredExecutors );
    }

    private final Monitors monitors;
    private final LogProvider userLogProvider;
    private final PageCache pageCache;
    private final ImmutableList<Class<?>> settingsClasses;
    private final ImmutableList<ExtensionFactory<?>> extensions;
    private final ImmutableMap<String,URLAccessRule> urlAccessRules;
    private final ImmutableList<QueryEngineProvider> queryEngineProviders;
    private final ImmutableList<Pair<DeferredExecutor, Group>> deferredExecutors;

    private GraphDatabaseDependencies(
            Monitors monitors,
            LogProvider userLogProvider,
            PageCache pageCache,
            ImmutableList<Class<?>> settingsClasses,
            ImmutableList<ExtensionFactory<?>> extensions,
            ImmutableMap<String,URLAccessRule> urlAccessRules,
            ImmutableList<QueryEngineProvider> queryEngineProviders,
            ImmutableList<Pair<DeferredExecutor, Group>> deferredExecutors
    )
    {
        this.monitors = monitors;
        this.userLogProvider = userLogProvider;
        this.pageCache = pageCache;
        this.settingsClasses = settingsClasses;
        this.extensions = extensions;
        this.urlAccessRules = urlAccessRules;
        this.queryEngineProviders = queryEngineProviders;
        this.deferredExecutors = deferredExecutors;
    }

    // Builder DSL
    public GraphDatabaseDependencies monitors( Monitors monitors )
    {
        return new GraphDatabaseDependencies( monitors, userLogProvider, pageCache, settingsClasses, extensions,
                urlAccessRules, queryEngineProviders, deferredExecutors );
    }

    public GraphDatabaseDependencies userLogProvider( LogProvider userLogProvider )
    {
        return new GraphDatabaseDependencies( monitors, userLogProvider, pageCache, settingsClasses, extensions,
                urlAccessRules, queryEngineProviders, deferredExecutors );
    }

    public GraphDatabaseDependencies pageCache( PageCache pageCache )
    {
        return new GraphDatabaseDependencies( monitors, userLogProvider, pageCache, settingsClasses, extensions, urlAccessRules, queryEngineProviders,
                deferredExecutors );
    }

    public GraphDatabaseDependencies withDeferredExecutor( DeferredExecutor executor, Group group )
    {
        return new GraphDatabaseDependencies( monitors, userLogProvider, pageCache, settingsClasses, extensions,
                urlAccessRules, queryEngineProviders,
                asImmutableList( concat( deferredExecutors, asIterable( Pair.of( executor, group ) ) ) ) );
    }

    public GraphDatabaseDependencies settingsClasses( List<Class<?>> settingsClasses )
    {
        return new GraphDatabaseDependencies( monitors, userLogProvider, pageCache, asImmutableList( settingsClasses ), extensions, urlAccessRules,
                queryEngineProviders, deferredExecutors );
    }

    public GraphDatabaseDependencies settingsClasses( Class<?>... settingsClass )
    {
        return new GraphDatabaseDependencies( monitors, userLogProvider, pageCache,
                asImmutableList( concat( settingsClasses, Arrays.asList( settingsClass ) ) ), extensions, urlAccessRules, queryEngineProviders,
                deferredExecutors );
    }

    public GraphDatabaseDependencies extensions( Iterable<ExtensionFactory<?>> extensions )
    {
        return new GraphDatabaseDependencies( monitors, userLogProvider, pageCache, settingsClasses,
                asImmutableList( extensions ), urlAccessRules, queryEngineProviders, deferredExecutors );
    }

    public GraphDatabaseDependencies urlAccessRules( Map<String,URLAccessRule> urlAccessRules )
    {
        final Map<String,URLAccessRule> newUrlAccessRules = this.urlAccessRules.toMap();
        newUrlAccessRules.putAll( urlAccessRules );
        return new GraphDatabaseDependencies( monitors, userLogProvider, pageCache, settingsClasses, extensions,
                asImmutableMap( newUrlAccessRules ), queryEngineProviders, deferredExecutors );
    }

    public GraphDatabaseDependencies queryEngineProviders( Iterable<QueryEngineProvider> queryEngineProviders )
    {
        return new GraphDatabaseDependencies( monitors, userLogProvider, pageCache, settingsClasses, extensions,
                urlAccessRules, asImmutableList( concat( this.queryEngineProviders, queryEngineProviders ) ),
                deferredExecutors );
    }

    // Dependencies implementation
    @Override
    public Monitors monitors()
    {
        return monitors;
    }

    @Override
    public LogProvider userLogProvider()
    {
        return userLogProvider;
    }

    @Override
    public Iterable<Class<?>> settingsClasses()
    {
        return settingsClasses;
    }

    @Override
    public Iterable<ExtensionFactory<?>> extensions()
    {
        return extensions;
    }

    @Override
    public Map<String,URLAccessRule> urlAccessRules()
    {
        return urlAccessRules.castToMap();
    }

    @Override
    public Iterable<QueryEngineProvider> executionEngines()
    {
        return queryEngineProviders;
    }

    @Override
    public Iterable<Pair<DeferredExecutor,Group>> deferredExecutors()
    {
        return deferredExecutors;
    }

    @Override
    public PageCache pageCache()
    {
        return pageCache;
    }

    // This method is needed to convert the non generic ExtensionFactory type returned from Service.load
    // to ExtensionFactory<?> generic types
    private static Iterator<ExtensionFactory<?>> getExtensions( Iterator<ExtensionFactory> parent )
    {
        return new Iterator<ExtensionFactory<?>>()
        {
            @Override
            public boolean hasNext()
            {
                return parent.hasNext();
            }

            @Override
            public ExtensionFactory<?> next()
            {
                return parent.next();
            }
        };
    }
}
