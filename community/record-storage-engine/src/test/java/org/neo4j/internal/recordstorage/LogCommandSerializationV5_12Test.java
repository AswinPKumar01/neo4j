/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [https://neo4j.com]
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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.neo4j.internal.recordstorage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.neo4j.internal.recordstorage.Command.RecordEnrichmentCommand;
import org.neo4j.kernel.impl.transaction.log.InMemoryClosableChannel;
import org.neo4j.storageengine.api.enrichment.Enrichment;
import org.neo4j.test.RandomSupport;
import org.neo4j.test.extension.Inject;
import org.neo4j.test.extension.RandomExtension;

@ExtendWith(RandomExtension.class)
public class LogCommandSerializationV5_12Test {

    @Inject
    private RandomSupport random;

    @Test
    void enrichmentSupported() throws IOException {
        final var metadata = LogCommandSerializationV5_8Test.metadata();

        final var entities = random.nextBytes(new byte[random.nextInt(1, 123)]);
        final var details = random.nextBytes(new byte[random.nextInt(1, 123)]);
        final var changes = random.nextBytes(new byte[random.nextInt(1, 123)]);
        final var values = random.nextBytes(new byte[random.nextInt(0, 123)]);
        final var userMetadata = random.nextBytes(new byte[random.nextInt(0, 123)]);

        try (var channel = new InMemoryClosableChannel()) {
            final var serialization = LogCommandSerializationV5_12.INSTANCE;

            final var writer = channel.writer();
            writer.beginChecksumForWriting();

            // write directly as version has now moved on
            writer.put(NeoCommandType.ENRICHMENT_COMMAND);
            metadata.serialize(writer);
            writer.putInt(entities.length);
            writer.putInt(details.length);
            writer.putInt(changes.length);
            writer.putInt(values.length);
            writer.putInt(userMetadata.length);
            writer.put(entities, entities.length);
            writer.put(details, details.length);
            writer.put(changes, changes.length);
            writer.put(values, values.length);
            writer.put(userMetadata, userMetadata.length);

            final var afterEnrichment = writer.getCurrentLogPosition();
            writer.putChecksum();

            final var command = serialization.read(channel);
            assertThat(command).isInstanceOf(RecordEnrichmentCommand.class);

            final var enrichment = (Enrichment.Read) ((RecordEnrichmentCommand) command).enrichment();
            LogCommandSerializationV5_8Test.assertMetadata(metadata, enrichment.metadata());
            assertBuffer(enrichment.entities(), entities);
            assertBuffer(enrichment.entityDetails(), details);
            assertBuffer(enrichment.entityChanges(), changes);
            assertBuffer(enrichment.values(), values);

            if (userMetadata.length == 0) {
                assertThat(enrichment.userMetadata()).isNotPresent();
            } else {
                assertBuffer(enrichment.userMetadata().orElseThrow(), userMetadata);
            }

            assertThat(channel.reader().getCurrentLogPosition())
                    .as("should have read the metadata and past the data buffers")
                    .isEqualTo(afterEnrichment);
        }
    }

    private static void assertBuffer(ByteBuffer actual, byte[] expected) {
        final var actualBytes = new byte[expected.length];
        actual.get(actualBytes);
        assertThat(actualBytes).isEqualTo(expected);
    }
}
