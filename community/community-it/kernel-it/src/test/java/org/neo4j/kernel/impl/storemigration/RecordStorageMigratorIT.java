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
package org.neo4j.kernel.impl.storemigration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.neo4j.configuration.GraphDatabaseInternalSettings.counts_store_max_cached_entries;
import static org.neo4j.configuration.GraphDatabaseSettings.DEFAULT_DATABASE_NAME;
import static org.neo4j.configuration.GraphDatabaseSettings.db_format;
import static org.neo4j.dbms.database.readonly.DatabaseReadOnlyChecker.writable;
import static org.neo4j.index.internal.gbptree.RecoveryCleanupWorkCollector.immediate;
import static org.neo4j.internal.batchimport.IndexImporterFactory.EMPTY;
import static org.neo4j.io.pagecache.context.CursorContext.NULL_CONTEXT;
import static org.neo4j.kernel.impl.transaction.log.LogTailMetadata.EMPTY_LOG_TAIL;
import static org.neo4j.logging.AssertableLogProvider.Level.ERROR;
import static org.neo4j.logging.LogAssertions.assertThat;
import static org.neo4j.memory.EmptyMemoryTracker.INSTANCE;
import static org.neo4j.storageengine.api.StoreVersionCheck.MigrationOutcome.MIGRATION_POSSIBLE;
import static org.neo4j.storageengine.migration.MigrationProgressMonitor.SILENT;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.neo4j.configuration.Config;
import org.neo4j.configuration.GraphDatabaseInternalSettings;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.exceptions.KernelException;
import org.neo4j.internal.batchimport.BatchImporterFactory;
import org.neo4j.internal.counts.GBPTreeGenericCountsStore;
import org.neo4j.internal.counts.GBPTreeRelationshipGroupDegreesStore;
import org.neo4j.internal.counts.RelationshipGroupDegreesStore;
import org.neo4j.internal.id.DefaultIdGeneratorFactory;
import org.neo4j.internal.id.ScanOnOpenOverwritingIdGeneratorFactory;
import org.neo4j.internal.recordstorage.RecordStorageEngineFactory;
import org.neo4j.io.ByteUnit;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.layout.DatabaseLayout;
import org.neo4j.io.layout.Neo4jLayout;
import org.neo4j.io.layout.recordstorage.RecordDatabaseLayout;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.io.pagecache.context.CursorContext;
import org.neo4j.io.pagecache.context.CursorContextFactory;
import org.neo4j.io.pagecache.context.EmptyVersionContextSupplier;
import org.neo4j.io.pagecache.tracing.PageCacheTracer;
import org.neo4j.kernel.database.DatabaseTracers;
import org.neo4j.kernel.impl.store.MetaDataStore;
import org.neo4j.kernel.impl.store.NeoStores;
import org.neo4j.kernel.impl.store.StoreFactory;
import org.neo4j.kernel.impl.store.StoreType;
import org.neo4j.kernel.impl.store.format.FormatFamily;
import org.neo4j.kernel.impl.store.format.RecordFormats;
import org.neo4j.kernel.impl.store.format.standard.Standard;
import org.neo4j.kernel.impl.store.format.standard.StandardV4_3;
import org.neo4j.kernel.impl.transaction.log.LogTailMetadata;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.kernel.recovery.LogTailExtractor;
import org.neo4j.logging.AssertableLogProvider;
import org.neo4j.logging.NullLogProvider;
import org.neo4j.logging.internal.LogService;
import org.neo4j.logging.internal.NullLogService;
import org.neo4j.logging.internal.SimpleLogService;
import org.neo4j.memory.MemoryTracker;
import org.neo4j.scheduler.JobScheduler;
import org.neo4j.storageengine.api.ExternalStoreId;
import org.neo4j.storageengine.api.MetadataProvider;
import org.neo4j.storageengine.api.StorageEngineFactory;
import org.neo4j.storageengine.api.StoreId;
import org.neo4j.storageengine.api.StoreVersion;
import org.neo4j.storageengine.api.StoreVersionCheck;
import org.neo4j.storageengine.api.cursor.StoreCursors;
import org.neo4j.storageengine.migration.MigrationProgressMonitor;
import org.neo4j.test.TestDatabaseManagementServiceBuilder;
import org.neo4j.test.extension.Inject;
import org.neo4j.test.extension.Neo4jLayoutExtension;
import org.neo4j.test.extension.pagecache.PageCacheExtension;
import org.neo4j.test.scheduler.ThreadPoolJobScheduler;
import org.neo4j.test.tags.RecordFormatOverrideTag;
import org.neo4j.test.utils.TestDirectory;

@PageCacheExtension
@Neo4jLayoutExtension
class RecordStorageMigratorIT {
    private static final String MIGRATION_DIRECTORY = StoreMigrator.MIGRATION_DIRECTORY;
    private static final Config CONFIG = Config.defaults(GraphDatabaseSettings.pagecache_memory, ByteUnit.mebiBytes(8));
    private static final long TX_ID = 51;

    @Inject
    private TestDirectory testDirectory;

    @Inject
    private PageCache pageCache;

    @Inject
    private RecordDatabaseLayout databaseLayout;

    private DatabaseLayout migrationLayout;
    private BatchImporterFactory batchImporterFactory;
    private FileSystemAbstraction fileSystem;

    private final MigrationProgressMonitor progressMonitor = SILENT;
    private final JobScheduler jobScheduler = new ThreadPoolJobScheduler();

    private static Stream<Arguments> versions() {
        return Stream.of(Arguments.of(StandardV4_3.RECORD_FORMATS));
    }

    @BeforeEach
    void setup() throws IOException {
        migrationLayout =
                Neo4jLayout.of(testDirectory.homePath(MIGRATION_DIRECTORY)).databaseLayout(DEFAULT_DATABASE_NAME);
        batchImporterFactory = BatchImporterFactory.withHighestPriority();
        fileSystem = testDirectory.getFileSystem();
        fileSystem.mkdirs(migrationLayout.databaseDirectory());
    }

    @AfterEach
    void tearDown() throws Exception {
        jobScheduler.close();
    }

    @ParameterizedTest
    @MethodSource("versions")
    void shouldBeAbleToResumeMigrationOnMoving(RecordFormats format) throws Exception {
        // GIVEN a legacy database
        Path prepare = testDirectory.directory("prepare");
        var fs = testDirectory.getFileSystem();
        MigrationTestUtils.prepareSampleLegacyDatabase(format, fs, databaseLayout, prepare);
        // and a state of the migration saying that it has done the actual migration
        LogService logService = NullLogService.getInstance();
        RecordStoreVersionCheck check = getVersionCheck(pageCache, databaseLayout);

        StoreVersion versionToMigrateFrom = getVersionToMigrateFrom(check);
        MigrationProgressMonitor progressMonitor = SILENT;
        PageCacheTracer cacheTracer = PageCacheTracer.NULL;
        CursorContextFactory contextFactory = new CursorContextFactory(cacheTracer, EmptyVersionContextSupplier.EMPTY);
        RecordStorageMigrator migrator = new RecordStorageMigrator(
                fs,
                pageCache,
                cacheTracer,
                CONFIG,
                logService,
                jobScheduler,
                contextFactory,
                batchImporterFactory,
                INSTANCE,
                false);
        migrator.migrate(
                databaseLayout,
                migrationLayout,
                progressMonitor.startSection("section"),
                versionToMigrateFrom,
                getVersionToMigrateTo(),
                EMPTY,
                EMPTY_LOG_TAIL);

        // WHEN simulating resuming the migration
        migrator = new RecordStorageMigrator(
                fs,
                pageCache,
                cacheTracer,
                CONFIG,
                logService,
                jobScheduler,
                contextFactory,
                batchImporterFactory,
                INSTANCE,
                false);
        migrator.moveMigratedFiles(migrationLayout, databaseLayout, versionToMigrateFrom, getVersionToMigrateTo());

        // THEN starting the new store should be successful
        StoreFactory storeFactory = new StoreFactory(
                databaseLayout,
                CONFIG,
                new ScanOnOpenOverwritingIdGeneratorFactory(fs, cacheTracer, databaseLayout.getDatabaseName()),
                pageCache,
                cacheTracer,
                fs,
                logService.getInternalLogProvider(),
                contextFactory,
                writable(),
                EMPTY_LOG_TAIL);
        storeFactory.openAllNeoStores().close();
    }

    @ParameterizedTest
    @MethodSource("versions")
    void keepExternalIdAndDatabaseIdOnMigration(RecordFormats format) throws IOException, KernelException {
        Path prepare = testDirectory.directory("prepare");
        var fs = testDirectory.getFileSystem();
        MigrationTestUtils.prepareSampleLegacyDatabase(format, fs, databaseLayout, prepare);

        LogService logService = NullLogService.getInstance();
        PageCacheTracer cacheTracer = PageCacheTracer.NULL;
        RecordStoreVersionCheck check = getVersionCheck(pageCache, databaseLayout);
        StoreVersion versionToMigrateFrom = getVersionToMigrateFrom(check);
        CursorContextFactory contextFactory = new CursorContextFactory(cacheTracer, EmptyVersionContextSupplier.EMPTY);

        StorageEngineFactory storageEngine = StorageEngineFactory.defaultStorageEngine();
        RecordStorageMigrator migrator = new RecordStorageMigrator(
                fs,
                pageCache,
                cacheTracer,
                CONFIG,
                logService,
                jobScheduler,
                contextFactory,
                batchImporterFactory,
                INSTANCE,
                false);
        StoreVersion migrateTo = getVersionToMigrateTo();
        migrator.migrate(
                databaseLayout,
                migrationLayout,
                progressMonitor.startSection("section"),
                versionToMigrateFrom,
                migrateTo,
                EMPTY,
                loadLogTail(databaseLayout, CONFIG, storageEngine));
        migrator.moveMigratedFiles(migrationLayout, databaseLayout, versionToMigrateFrom, migrateTo);

        StoreFactory storeFactory = new StoreFactory(
                databaseLayout,
                CONFIG,
                new ScanOnOpenOverwritingIdGeneratorFactory(fs, cacheTracer, databaseLayout.getDatabaseName()),
                pageCache,
                cacheTracer,
                fs,
                logService.getInternalLogProvider(),
                contextFactory,
                writable(),
                loadLogTail(databaseLayout, CONFIG, storageEngine));
        try (NeoStores neoStores = storeFactory.openAllNeoStores()) {
            MetaDataStore metaDataStore = neoStores.getMetaDataStore();
            assertEquals(
                    new UUID(1, 2),
                    metaDataStore.getDatabaseIdUuid(NULL_CONTEXT).orElseThrow());
            assertEquals(new UUID(3, 4), metaDataStore.getExternalStoreId().getId());
        }
    }

    @Test
    void changeStoreIdOnMigration() throws IOException, KernelException {
        Path prepare = testDirectory.directory("prepare");
        var fs = testDirectory.getFileSystem();
        MigrationTestUtils.prepareSampleLegacyDatabase(StandardV4_3.RECORD_FORMATS, fs, databaseLayout, prepare);

        LogService logService = NullLogService.getInstance();
        PageCacheTracer cacheTracer = PageCacheTracer.NULL;
        RecordStoreVersionCheck check = getVersionCheck(pageCache, databaseLayout);
        StoreVersion versionToMigrateFrom = getVersionToMigrateFrom(check);
        CursorContextFactory contextFactory = new CursorContextFactory(cacheTracer, EmptyVersionContextSupplier.EMPTY);

        StorageEngineFactory storageEngine = StorageEngineFactory.defaultStorageEngine();
        RecordStorageMigrator migrator = new RecordStorageMigrator(
                fs,
                pageCache,
                cacheTracer,
                CONFIG,
                logService,
                jobScheduler,
                contextFactory,
                batchImporterFactory,
                INSTANCE,
                false);
        StoreVersion migrateTo = getVersionToMigrateTo();
        migrator.migrate(
                databaseLayout,
                migrationLayout,
                progressMonitor.startSection("section"),
                versionToMigrateFrom,
                migrateTo,
                EMPTY,
                loadLogTail(databaseLayout, CONFIG, storageEngine));
        migrator.moveMigratedFiles(migrationLayout, databaseLayout, versionToMigrateFrom, migrateTo);

        StoreFactory storeFactory = new StoreFactory(
                databaseLayout,
                CONFIG,
                new ScanOnOpenOverwritingIdGeneratorFactory(fs, cacheTracer, databaseLayout.getDatabaseName()),
                pageCache,
                cacheTracer,
                fs,
                logService.getInternalLogProvider(),
                contextFactory,
                writable(),
                loadLogTail(databaseLayout, CONFIG, storageEngine));
        try (NeoStores neoStores = storeFactory.openAllNeoStores()) {
            MetaDataStore metaDataStore = neoStores.getMetaDataStore();
            StoreId storeId = metaDataStore.getStoreId();
            assertNotEquals(1155255428148939479L, storeId.getRandom());
            RecordStoreVersion storeVersion = (RecordStoreVersion)
                    storageEngine.versionInformation(storeId).orElseThrow();
            assertEquals(Standard.LATEST_NAME, storeVersion.getFormat().name());
        }
    }

    @Test
    @RecordFormatOverrideTag
    void keepIdsOnUpgrade() throws IOException, KernelException {
        StoreId storeId;
        ExternalStoreId externalStoreId;
        UUID databaseUUID = UUID.randomUUID();
        var dbms = new TestDatabaseManagementServiceBuilder(databaseLayout)
                .setConfig(db_format, FormatFamily.STANDARD.name())
                .build();
        try {
            GraphDatabaseAPI database = (GraphDatabaseAPI) dbms.database(DEFAULT_DATABASE_NAME);
            MetadataProvider metadataProvider =
                    database.getDependencyResolver().resolveDependency(MetadataProvider.class);
            storeId = metadataProvider.getStoreId();
            externalStoreId = metadataProvider.getExternalStoreId();

            metadataProvider.setDatabaseIdUuid(databaseUUID, NULL_CONTEXT);
        } finally {
            dbms.shutdown();
        }

        LogService logService = NullLogService.getInstance();
        PageCacheTracer cacheTracer = PageCacheTracer.NULL;
        CursorContextFactory contextFactory = new CursorContextFactory(cacheTracer, EmptyVersionContextSupplier.EMPTY);

        StandardFormatWithMinorVersionBump toFormat = new StandardFormatWithMinorVersionBump();
        RecordStoreVersion versionToMigrateFrom = new RecordStoreVersion(Standard.LATEST_RECORD_FORMATS);
        RecordStoreVersion versionToMigrateTo = new RecordStoreVersion(toFormat);

        Config config = Config.defaults(GraphDatabaseSettings.pagecache_memory, ByteUnit.mebiBytes(8));
        config.set(GraphDatabaseInternalSettings.include_versions_under_development, true);

        StorageEngineFactory storageEngine = StorageEngineFactory.defaultStorageEngine();
        FileSystemAbstraction fs = testDirectory.getFileSystem();
        RecordStorageMigrator migrator = new RecordStorageMigrator(
                fs,
                pageCache,
                cacheTracer,
                config,
                logService,
                jobScheduler,
                contextFactory,
                batchImporterFactory,
                INSTANCE,
                false);
        migrator.migrate(
                databaseLayout,
                migrationLayout,
                progressMonitor.startSection("section"),
                versionToMigrateFrom,
                versionToMigrateTo,
                EMPTY,
                loadLogTail(databaseLayout, config, storageEngine));
        migrator.moveMigratedFiles(migrationLayout, databaseLayout, versionToMigrateFrom, versionToMigrateTo);

        StoreFactory storeFactory = new StoreFactory(
                databaseLayout,
                config,
                new ScanOnOpenOverwritingIdGeneratorFactory(fs, cacheTracer, databaseLayout.getDatabaseName()),
                pageCache,
                cacheTracer,
                fs,
                logService.getInternalLogProvider(),
                contextFactory,
                writable(),
                loadLogTail(databaseLayout, config, storageEngine));
        try (NeoStores neoStores = storeFactory.openAllNeoStores()) {
            MetaDataStore metaDataStore = neoStores.getMetaDataStore();
            StoreId newStoreId = metaDataStore.getStoreId();
            // Store version should be updated, and the rest should be as before
            RecordStoreVersion storeVersion = (RecordStoreVersion)
                    storageEngine.versionInformation(newStoreId).orElseThrow();
            assertEquals(toFormat.name(), storeVersion.getFormat().name());
            assertEquals(storeId.getRandom(), newStoreId.getRandom());
            assertEquals(
                    databaseUUID, metaDataStore.getDatabaseIdUuid(NULL_CONTEXT).orElseThrow());
            assertEquals(externalStoreId, metaDataStore.getExternalStoreId());
        }
    }

    @ParameterizedTest
    @MethodSource("versions")
    void shouldBeAbleToMigrateWithoutErrors(RecordFormats format) throws Exception {
        // GIVEN a legacy database

        Path prepare = testDirectory.directory("prepare");
        var fs = testDirectory.getFileSystem();
        MigrationTestUtils.prepareSampleLegacyDatabase(format, fs, databaseLayout, prepare);

        AssertableLogProvider logProvider = new AssertableLogProvider(true);
        LogService logService = new SimpleLogService(logProvider, logProvider);

        RecordStoreVersionCheck check = getVersionCheck(pageCache, databaseLayout);

        StoreVersion versionToMigrateFrom = getVersionToMigrateFrom(check);
        PageCacheTracer cacheTracer = PageCacheTracer.NULL;
        CursorContextFactory contextFactory = new CursorContextFactory(cacheTracer, EmptyVersionContextSupplier.EMPTY);
        RecordStorageMigrator migrator = new RecordStorageMigrator(
                fs,
                pageCache,
                cacheTracer,
                CONFIG,
                logService,
                jobScheduler,
                contextFactory,
                batchImporterFactory,
                INSTANCE,
                false);

        // WHEN migrating
        var engineFactory = StorageEngineFactory.defaultStorageEngine();
        var logTailMetadata = loadLogTail(databaseLayout, CONFIG, engineFactory);
        migrator.migrate(
                databaseLayout,
                migrationLayout,
                progressMonitor.startSection("section"),
                versionToMigrateFrom,
                getVersionToMigrateTo(),
                EMPTY,
                logTailMetadata);
        migrator.moveMigratedFiles(migrationLayout, databaseLayout, versionToMigrateFrom, getVersionToMigrateTo());

        // THEN starting the new store should be successful
        assertThat(testDirectory.getFileSystem().fileExists(databaseLayout.relationshipGroupDegreesStore()))
                .isTrue();
        GBPTreeRelationshipGroupDegreesStore.DegreesRebuilder noRebuildAssertion =
                new GBPTreeRelationshipGroupDegreesStore.DegreesRebuilder() {
                    @Override
                    public void rebuild(
                            RelationshipGroupDegreesStore.Updater updater,
                            CursorContext cursorContext,
                            MemoryTracker memoryTracker) {
                        throw new IllegalStateException("Rebuild should not be required");
                    }

                    @Override
                    public long lastCommittedTxId() {
                        try {
                            return new RecordStorageEngineFactory()
                                    .readOnlyTransactionIdStore(logTailMetadata)
                                    .getLastCommittedTransactionId();
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    }
                };
        var migratedStoreOpenOptions = engineFactory.getStoreOpenOptions(fs, pageCache, databaseLayout, contextFactory);
        try (var groupDegreesStore = new GBPTreeRelationshipGroupDegreesStore(
                pageCache,
                databaseLayout.relationshipGroupDegreesStore(),
                testDirectory.getFileSystem(),
                immediate(),
                noRebuildAssertion,
                writable(),
                GBPTreeGenericCountsStore.NO_MONITOR,
                databaseLayout.getDatabaseName(),
                counts_store_max_cached_entries.defaultValue(),
                NullLogProvider.getInstance(),
                contextFactory,
                cacheTracer,
                migratedStoreOpenOptions)) {
            // The rebuild would happen here in start and will throw exception (above) if invoked
            groupDegreesStore.start(NULL_CONTEXT, StoreCursors.NULL, INSTANCE);

            // The store keeps track of committed transactions.
            // It is essential that it starts with the transaction
            // that is the last committed one at the upgrade time.
            assertEquals(TX_ID, groupDegreesStore.txId());
        }

        StoreFactory storeFactory = new StoreFactory(
                databaseLayout,
                CONFIG,
                new ScanOnOpenOverwritingIdGeneratorFactory(fs, cacheTracer, databaseLayout.getDatabaseName()),
                pageCache,
                cacheTracer,
                fs,
                logService.getInternalLogProvider(),
                contextFactory,
                writable(),
                loadLogTail(databaseLayout, CONFIG, engineFactory));
        storeFactory.openAllNeoStores().close();
        assertThat(logProvider).forLevel(ERROR).doesNotHaveAnyLogs();
    }

    @ParameterizedTest
    @MethodSource("versions")
    void shouldBeAbleToResumeMigrationOnRebuildingCounts(RecordFormats format) throws Exception {
        // GIVEN a legacy database

        Path prepare = testDirectory.directory("prepare");
        var fs = testDirectory.getFileSystem();
        MigrationTestUtils.prepareSampleLegacyDatabase(format, fs, databaseLayout, prepare);
        // and a state of the migration saying that it has done the actual migration
        LogService logService = NullLogService.getInstance();
        RecordStoreVersionCheck check = getVersionCheck(pageCache, databaseLayout);

        StoreVersion versionToMigrateFrom = getVersionToMigrateFrom(check);
        MigrationProgressMonitor progressMonitor = SILENT;
        PageCacheTracer cacheTracer = PageCacheTracer.NULL;
        var contextFactory = new CursorContextFactory(cacheTracer, EmptyVersionContextSupplier.EMPTY);
        RecordStorageMigrator migrator = new RecordStorageMigrator(
                fs,
                pageCache,
                cacheTracer,
                CONFIG,
                logService,
                jobScheduler,
                contextFactory,
                batchImporterFactory,
                INSTANCE,
                false);
        migrator.migrate(
                databaseLayout,
                migrationLayout,
                progressMonitor.startSection("section"),
                versionToMigrateFrom,
                getVersionToMigrateTo(),
                EMPTY,
                EMPTY_LOG_TAIL);

        // WHEN simulating resuming the migration

        migrator.moveMigratedFiles(migrationLayout, databaseLayout, versionToMigrateFrom, getVersionToMigrateTo());

        // THEN starting the new store should be successful
        StoreFactory storeFactory = new StoreFactory(
                databaseLayout,
                CONFIG,
                new ScanOnOpenOverwritingIdGeneratorFactory(fs, cacheTracer, databaseLayout.getDatabaseName()),
                pageCache,
                cacheTracer,
                fs,
                logService.getInternalLogProvider(),
                contextFactory,
                writable(),
                EMPTY_LOG_TAIL);
        storeFactory.openAllNeoStores().close();
    }

    @ParameterizedTest
    @MethodSource("versions")
    void shouldStartCheckpointLogVersionFromZeroIfMissingBeforeMigration(RecordFormats format) throws Exception {
        // given
        Path prepare = testDirectory.directory("prepare");
        var fs = testDirectory.getFileSystem();
        MigrationTestUtils.prepareSampleLegacyDatabase(format, fs, databaseLayout, prepare);
        RecordStoreVersionCheck check = getVersionCheck(pageCache, databaseLayout);
        StoreVersion versionToMigrateFrom = getVersionToMigrateFrom(check);
        StoreVersion versionToMigrateTo = getVersionToMigrateTo();
        PageCacheTracer cacheTracer = PageCacheTracer.NULL;
        var contextFactory = new CursorContextFactory(cacheTracer, EmptyVersionContextSupplier.EMPTY);

        // when
        RecordStorageMigrator migrator = new RecordStorageMigrator(
                fs,
                pageCache,
                cacheTracer,
                CONFIG,
                NullLogService.getInstance(),
                jobScheduler,
                contextFactory,
                batchImporterFactory,
                INSTANCE,
                false);

        // when
        migrator.migrate(
                databaseLayout,
                migrationLayout,
                SILENT.startSection("section"),
                versionToMigrateFrom,
                versionToMigrateTo,
                EMPTY,
                EMPTY_LOG_TAIL);
        migrator.moveMigratedFiles(migrationLayout, databaseLayout, versionToMigrateFrom, versionToMigrateTo);

        // then
        try (NeoStores neoStores = new StoreFactory(
                        databaseLayout,
                        Config.defaults(),
                        new DefaultIdGeneratorFactory(fs, immediate(), cacheTracer, databaseLayout.getDatabaseName()),
                        pageCache,
                        cacheTracer,
                        fs,
                        NullLogProvider.getInstance(),
                        contextFactory,
                        writable(),
                        EMPTY_LOG_TAIL)
                .openNeoStores(StoreType.META_DATA)) {
            neoStores.start(NULL_CONTEXT);
            assertThat(neoStores.getMetaDataStore().getCheckpointLogVersion()).isEqualTo(0);
        }
    }

    private static StoreVersion getVersionToMigrateFrom(RecordStoreVersionCheck check) {
        StoreVersionCheck.MigrationCheckResult result = check.getAndCheckMigrationTargetVersion(null, NULL_CONTEXT);
        assertEquals(result.outcome(), MIGRATION_POSSIBLE);
        return StorageEngineFactory.defaultStorageEngine()
                .versionInformation(result.versionToMigrateFrom())
                .orElseThrow();
    }

    private static StoreVersion getVersionToMigrateTo() {
        return new RecordStoreVersion(Standard.LATEST_RECORD_FORMATS);
    }

    private static RecordStoreVersionCheck getVersionCheck(PageCache pageCache, RecordDatabaseLayout layout) {
        return new RecordStoreVersionCheck(pageCache, layout, Config.defaults());
    }

    private LogTailMetadata loadLogTail(DatabaseLayout layout, Config config, StorageEngineFactory engineFactory)
            throws IOException {
        return new LogTailExtractor(fileSystem, pageCache, config, engineFactory, DatabaseTracers.EMPTY)
                .getTailMetadata(layout, INSTANCE);
    }
}
