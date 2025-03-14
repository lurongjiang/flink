/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.state;

import org.apache.flink.api.common.JobID;
import org.apache.flink.core.memory.MemorySegmentFactory;
import org.apache.flink.runtime.checkpoint.CheckpointOptions;
import org.apache.flink.runtime.checkpoint.OperatorSubtaskState;
import org.apache.flink.runtime.checkpoint.TaskStateSnapshot;
import org.apache.flink.runtime.checkpoint.channel.ChannelStateWriteRequestExecutorFactory;
import org.apache.flink.runtime.checkpoint.channel.ChannelStateWriter.ChannelStateWriteResult;
import org.apache.flink.runtime.checkpoint.channel.ChannelStateWriterImpl;
import org.apache.flink.runtime.checkpoint.channel.InputChannelInfo;
import org.apache.flink.runtime.checkpoint.channel.ResultSubpartitionInfo;
import org.apache.flink.runtime.checkpoint.channel.SequentialChannelStateReader;
import org.apache.flink.runtime.checkpoint.channel.SequentialChannelStateReaderImpl;
import org.apache.flink.runtime.io.network.buffer.Buffer;
import org.apache.flink.runtime.io.network.buffer.FreeingBufferRecycler;
import org.apache.flink.runtime.io.network.buffer.NetworkBuffer;
import org.apache.flink.runtime.io.network.buffer.NetworkBufferPool;
import org.apache.flink.runtime.io.network.partition.BufferWritingResultPartition;
import org.apache.flink.runtime.io.network.partition.NoOpBufferAvailablityListener;
import org.apache.flink.runtime.io.network.partition.ResultPartition;
import org.apache.flink.runtime.io.network.partition.ResultPartitionBuilder;
import org.apache.flink.runtime.io.network.partition.ResultPartitionType;
import org.apache.flink.runtime.io.network.partition.ResultSubpartition.BufferAndBacklog;
import org.apache.flink.runtime.io.network.partition.ResultSubpartitionIndexSet;
import org.apache.flink.runtime.io.network.partition.ResultSubpartitionView;
import org.apache.flink.runtime.io.network.partition.consumer.BufferOrEvent;
import org.apache.flink.runtime.io.network.partition.consumer.InputChannelBuilder;
import org.apache.flink.runtime.io.network.partition.consumer.InputGate;
import org.apache.flink.runtime.io.network.partition.consumer.SingleInputGate;
import org.apache.flink.runtime.io.network.partition.consumer.SingleInputGateBuilder;
import org.apache.flink.runtime.jobgraph.JobVertexID;
import org.apache.flink.runtime.jobgraph.OperatorID;
import org.apache.flink.runtime.state.storage.JobManagerCheckpointStorage;
import org.apache.flink.util.function.SupplierWithException;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.apache.flink.runtime.checkpoint.CheckpointType.CHECKPOINT;
import static org.apache.flink.runtime.checkpoint.channel.ChannelStateWriter.SEQUENCE_NUMBER_UNKNOWN;
import static org.apache.flink.runtime.io.network.buffer.Buffer.DataType.RECOVERY_COMPLETION;
import static org.apache.flink.runtime.state.ChannelStateHelper.castToInputStateCollection;
import static org.apache.flink.runtime.state.ChannelStateHelper.castToOutputStateCollection;
import static org.apache.flink.util.CloseableIterator.ofElements;
import static org.assertj.core.api.Assertions.assertThat;

/** ChannelPersistenceITCase. */
class ChannelPersistenceITCase {
    private static final Random RANDOM = new Random(System.currentTimeMillis());
    private static final JobID JOB_ID = new JobID();
    private static final JobVertexID JOB_VERTEX_ID = new JobVertexID();
    private static final int SUBTASK_INDEX = 0;

    private static final CheckpointStorage CHECKPOINT_STORAGE = new JobManagerCheckpointStorage();

    @Test
    void testUpstreamBlocksAfterRecoveringState() throws Exception {
        upstreamBlocksAfterRecoveringState(ResultPartitionType.PIPELINED);
    }

    @Test
    void testNotBlocksAfterRecoveringStateForApproximateLocalRecovery() throws Exception {
        upstreamBlocksAfterRecoveringState(ResultPartitionType.PIPELINED_APPROXIMATE);
    }

    @Test
    void testReadWritten() throws Exception {
        byte[] inputChannelInfoData = randomBytes(1024);
        byte[] resultSubpartitionInfoData = randomBytes(1024);
        byte[] resultSubpartitionInfoFutureData = randomBytes(1024);
        int partitionIndex = 0;

        SequentialChannelStateReader reader =
                new SequentialChannelStateReaderImpl(
                        toTaskStateSnapshot(
                                write(
                                        1L,
                                        singletonMap(
                                                new InputChannelInfo(0, 0), inputChannelInfoData),
                                        singletonMap(
                                                new ResultSubpartitionInfo(partitionIndex, 0),
                                                resultSubpartitionInfoData),
                                        singletonMap(
                                                new ResultSubpartitionInfo(partitionIndex, 1),
                                                resultSubpartitionInfoFutureData))));

        NetworkBufferPool networkBufferPool = new NetworkBufferPool(6, 1024);
        try {
            int numChannels = 1;
            InputGate gate = buildGate(networkBufferPool, numChannels);
            reader.readInputData(new InputGate[] {gate});
            assertThat(collectBytes(gate::pollNext, BufferOrEvent::getBuffer))
                    .isEqualTo(inputChannelInfoData);

            int subpartitions = 2;
            BufferWritingResultPartition resultPartition =
                    buildResultPartition(
                            networkBufferPool,
                            ResultPartitionType.PIPELINED,
                            partitionIndex,
                            subpartitions);
            reader.readOutputData(new BufferWritingResultPartition[] {resultPartition}, false);
            ResultSubpartitionView view =
                    resultPartition.createSubpartitionView(
                            new ResultSubpartitionIndexSet(0), new NoOpBufferAvailablityListener());
            assertThat(
                            collectBytes(
                                    () -> Optional.ofNullable(view.getNextBuffer()),
                                    BufferAndBacklog::buffer))
                    .isEqualTo(resultSubpartitionInfoData);
            ResultSubpartitionView futureView =
                    resultPartition.createSubpartitionView(
                            new ResultSubpartitionIndexSet(1), new NoOpBufferAvailablityListener());
            assertThat(
                            collectBytes(
                                    () -> Optional.ofNullable(futureView.getNextBuffer()),
                                    BufferAndBacklog::buffer))
                    .isEqualTo(resultSubpartitionInfoFutureData);
        } finally {
            networkBufferPool.destroy();
        }
    }

    private void upstreamBlocksAfterRecoveringState(ResultPartitionType type) throws Exception {
        NetworkBufferPool networkBufferPool = new NetworkBufferPool(4, 1024);
        byte[] dataAfterRecovery = randomBytes(1024);
        try {
            BufferWritingResultPartition resultPartition =
                    buildResultPartition(networkBufferPool, type, 0, 1);
            new SequentialChannelStateReaderImpl(new TaskStateSnapshot())
                    .readOutputData(new BufferWritingResultPartition[] {resultPartition}, true);
            resultPartition.emitRecord(ByteBuffer.wrap(dataAfterRecovery), 0);
            ResultSubpartitionView view =
                    resultPartition.createSubpartitionView(
                            new ResultSubpartitionIndexSet(0), new NoOpBufferAvailablityListener());
            if (type != ResultPartitionType.PIPELINED_APPROXIMATE) {
                assertThat(view.getNextBuffer().buffer().getDataType())
                        .isEqualTo(RECOVERY_COMPLETION);
                assertThat(view.getNextBuffer()).isNull();
                view.resumeConsumption();
            }
            assertThat(collectBytes(view.getNextBuffer().buffer())).isEqualTo(dataAfterRecovery);
        } finally {
            networkBufferPool.destroy();
        }
    }

    private BufferWritingResultPartition buildResultPartition(
            NetworkBufferPool networkBufferPool,
            ResultPartitionType resultPartitionType,
            int index,
            int numberOfSubpartitions)
            throws IOException {
        ResultPartition resultPartition =
                new ResultPartitionBuilder()
                        .setResultPartitionIndex(index)
                        .setResultPartitionType(resultPartitionType)
                        .setNumberOfSubpartitions(numberOfSubpartitions)
                        .setBufferPoolFactory(
                                () ->
                                        networkBufferPool.createBufferPool(
                                                numberOfSubpartitions,
                                                Integer.MAX_VALUE,
                                                numberOfSubpartitions,
                                                Integer.MAX_VALUE,
                                                0))
                        .build();
        resultPartition.setup();
        return (BufferWritingResultPartition) resultPartition;
    }

    private SingleInputGate buildGate(NetworkBufferPool networkBufferPool, int numberOfChannels)
            throws IOException {
        SingleInputGate gate =
                new SingleInputGateBuilder()
                        .setChannelFactory(InputChannelBuilder::buildRemoteRecoveredChannel)
                        .setBufferPoolFactory(
                                networkBufferPool.createBufferPool(
                                        numberOfChannels, Integer.MAX_VALUE))
                        .setSegmentProvider(networkBufferPool)
                        .setNumberOfChannels(numberOfChannels)
                        .build();
        gate.setup();
        return gate;
    }

    private <T> byte[] collectBytes(
            SupplierWithException<Optional<T>, Exception> entrySupplier,
            Function<T, Buffer> bufferExtractor)
            throws Exception {
        ArrayList<Buffer> buffers = new ArrayList<>();
        for (Optional<T> entry = entrySupplier.get();
                entry.isPresent();
                entry = entrySupplier.get()) {
            entry.map(bufferExtractor)
                    .filter(buffer -> buffer.getDataType().isBuffer())
                    .ifPresent(buffers::add);
        }
        ByteBuffer result =
                ByteBuffer.wrap(new byte[buffers.stream().mapToInt(Buffer::getSize).sum()]);
        buffers.forEach(
                buffer -> {
                    result.put(buffer.getNioBufferReadable());
                    buffer.recycleBuffer();
                });
        return result.array();
    }

    private byte[] collectBytes(Buffer buffer) {
        ByteBuffer nioBufferReadable = buffer.getNioBufferReadable();
        byte[] buf = new byte[nioBufferReadable.capacity()];
        nioBufferReadable.get(buf);
        return buf;
    }

    private byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private ChannelStateWriteResult write(
            long checkpointId,
            Map<InputChannelInfo, byte[]> icMap,
            Map<ResultSubpartitionInfo, byte[]> rsMap,
            Map<ResultSubpartitionInfo, byte[]> rsFutureMap)
            throws Exception {
        int maxStateSize =
                sizeOfBytes(icMap) + sizeOfBytes(rsMap) + sizeOfBytes(rsFutureMap) + Long.BYTES * 3;
        Map<InputChannelInfo, Buffer> icBuffers = wrapWithBuffers(icMap);
        Map<ResultSubpartitionInfo, Buffer> rsBuffers = wrapWithBuffers(rsMap);
        Map<ResultSubpartitionInfo, Buffer> rsFutureBuffers = wrapWithBuffers(rsFutureMap);
        try (ChannelStateWriterImpl writer =
                new ChannelStateWriterImpl(
                        JOB_VERTEX_ID,
                        "test",
                        SUBTASK_INDEX,
                        () -> CHECKPOINT_STORAGE.createCheckpointStorage(JOB_ID),
                        new ChannelStateWriteRequestExecutorFactory(JOB_ID),
                        5)) {
            writer.start(
                    checkpointId,
                    new CheckpointOptions(
                            CHECKPOINT,
                            new CheckpointStorageLocationReference(
                                    "poly".getBytes(StandardCharsets.UTF_8))));
            for (Map.Entry<InputChannelInfo, Buffer> e : icBuffers.entrySet()) {
                writer.addInputData(
                        checkpointId,
                        e.getKey(),
                        SEQUENCE_NUMBER_UNKNOWN,
                        ofElements(Buffer::recycleBuffer, e.getValue()));
            }
            writer.finishInput(checkpointId);
            for (Map.Entry<ResultSubpartitionInfo, Buffer> e : rsFutureBuffers.entrySet()) {
                CompletableFuture<List<Buffer>> dataFuture = new CompletableFuture<>();
                writer.addOutputDataFuture(
                        checkpointId, e.getKey(), SEQUENCE_NUMBER_UNKNOWN, dataFuture);
                dataFuture.complete(singletonList(e.getValue()));
            }
            for (Map.Entry<ResultSubpartitionInfo, Buffer> e : rsBuffers.entrySet()) {
                writer.addOutputData(
                        checkpointId, e.getKey(), SEQUENCE_NUMBER_UNKNOWN, e.getValue());
            }
            writer.finishOutput(checkpointId);
            ChannelStateWriteResult result = writer.getAndRemoveWriteResult(checkpointId);
            result.getResultSubpartitionStateHandles().join(); // prevent abnormal complete in close
            return result;
        }
    }

    private TaskStateSnapshot toTaskStateSnapshot(ChannelStateWriteResult t) throws Exception {
        return new TaskStateSnapshot(
                singletonMap(
                        new OperatorID(),
                        OperatorSubtaskState.builder()
                                .setInputChannelState(
                                        castToInputStateCollection(
                                                t.getInputChannelStateHandles().get()))
                                .setResultSubpartitionState(
                                        castToOutputStateCollection(
                                                t.getResultSubpartitionStateHandles().get()))
                                .build()));
    }

    private static int sizeOfBytes(Map<?, byte[]> map) {
        return map.values().stream().mapToInt(d -> d.length).sum();
    }

    private <K> Map<K, Buffer> wrapWithBuffers(Map<K, byte[]> icMap) {
        return icMap.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> wrapWithBuffer(e.getValue())));
    }

    private static Buffer wrapWithBuffer(byte[] data) {
        NetworkBuffer buffer =
                new NetworkBuffer(
                        MemorySegmentFactory.allocateUnpooledSegment(data.length, null),
                        FreeingBufferRecycler.INSTANCE);
        buffer.writeBytes(data);
        return buffer;
    }
}
