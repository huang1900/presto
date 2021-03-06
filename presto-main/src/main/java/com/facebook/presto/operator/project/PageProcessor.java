/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.operator.project;

import com.facebook.presto.array.ReferenceCountMap;
import com.facebook.presto.operator.DriverYieldSignal;
import com.facebook.presto.spi.ConnectorSession;
import com.facebook.presto.spi.Page;
import com.facebook.presto.spi.block.Block;
import com.facebook.presto.spi.block.DictionaryBlock;
import com.facebook.presto.spi.block.DictionaryId;
import com.facebook.presto.spi.block.LazyBlock;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.AbstractIterator;

import javax.annotation.concurrent.NotThreadSafe;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static com.facebook.presto.operator.project.PageProcessorOutput.EMPTY_PAGE_PROCESSOR_OUTPUT;
import static com.facebook.presto.operator.project.SelectedPositions.positionsRange;
import static com.facebook.presto.spi.block.DictionaryId.randomDictionaryId;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Iterators.singletonIterator;
import static java.util.Objects.requireNonNull;

@NotThreadSafe
public class PageProcessor
{
    static final int MAX_BATCH_SIZE = 8 * 1024;
    static final int MAX_PAGE_SIZE_IN_BYTES = 4 * 1024 * 1024;
    static final int MIN_PAGE_SIZE_IN_BYTES = 1024 * 1024;

    private final DictionarySourceIdFunction dictionarySourceIdFunction = new DictionarySourceIdFunction();
    private final Optional<PageFilter> filter;
    private final List<PageProjection> projections;

    private int projectBatchSize = MAX_BATCH_SIZE;

    public PageProcessor(Optional<PageFilter> filter, List<? extends PageProjection> projections)
    {
        this.filter = requireNonNull(filter, "filter is null")
                .map(pageFilter -> {
                    if (pageFilter.getInputChannels().size() == 1 && pageFilter.isDeterministic()) {
                        return new DictionaryAwarePageFilter(pageFilter);
                    }
                    return pageFilter;
                });
        this.projections = requireNonNull(projections, "projections is null").stream()
                .map(projection -> {
                    if (projection.getInputChannels().size() == 1 && projection.isDeterministic()) {
                        return new DictionaryAwarePageProjection(projection, dictionarySourceIdFunction);
                    }
                    return projection;
                })
                .collect(toImmutableList());
    }

    public PageProcessorOutput process(ConnectorSession session, DriverYieldSignal yieldSignal, Page page)
    {
        // limit the scope of the dictionary ids to just one page
        dictionarySourceIdFunction.reset();

        if (page.getPositionCount() == 0) {
            return EMPTY_PAGE_PROCESSOR_OUTPUT;
        }

        if (filter.isPresent()) {
            SelectedPositions selectedPositions = filter.get().filter(session, filter.get().getInputChannels().getInputChannels(page));
            if (selectedPositions.isEmpty()) {
                return EMPTY_PAGE_PROCESSOR_OUTPUT;
            }

            if (projections.isEmpty()) {
                return new PageProcessorOutput(() -> calculateRetainedSizeWithoutLoading(page), singletonIterator(Optional.of(new Page(selectedPositions.size()))));
            }

            if (selectedPositions.size() != page.getPositionCount()) {
                PositionsPageProcessorIterator pages = new PositionsPageProcessorIterator(session, yieldSignal, page, selectedPositions);
                return new PageProcessorOutput(pages::getRetainedSizeInBytes, pages);
            }
        }

        PositionsPageProcessorIterator pages = new PositionsPageProcessorIterator(session, yieldSignal, page, positionsRange(0, page.getPositionCount()));
        return new PageProcessorOutput(pages::getRetainedSizeInBytes, pages);
    }

    @VisibleForTesting
    public List<PageProjection> getProjections()
    {
        return projections;
    }

    private static boolean isUnloadedLazyBlock(Block block)
    {
        return (block instanceof LazyBlock) && !((LazyBlock) block).isLoaded();
    }

    private static long calculateRetainedSizeWithoutLoading(Page page)
    {
        long retainedSizeInBytes = 0;
        for (Block block : page.getBlocks()) {
            if (!isUnloadedLazyBlock(block)) {
                retainedSizeInBytes += block.getRetainedSizeInBytes();
            }
        }
        return retainedSizeInBytes;
    }

    private class PositionsPageProcessorIterator
            extends AbstractIterator<Optional<Page>>
    {
        private final ConnectorSession session;
        private final DriverYieldSignal yieldSignal;
        private final Page page;

        private SelectedPositions selectedPositions;
        private final Block[] previouslyComputedResults;
        private long retainedSizeInBytes;

        // remember if we need to re-use the same batch size if we yield last time
        // TODO: make this a local variable in computeNext
        // processBatch() should return multiple values instead of using a field variable as part of its return
        private boolean forceYieldFinish;
        private int previousBatchSize;
        private Optional<PageProjectionOutput> pageProjectOutput = Optional.empty();

        public PositionsPageProcessorIterator(ConnectorSession session, DriverYieldSignal yieldSignal, Page page, SelectedPositions selectedPositions)
        {
            this.session = session;
            this.yieldSignal = yieldSignal;
            this.page = page;
            this.selectedPositions = selectedPositions;
            this.previouslyComputedResults = new Block[projections.size()];
            updateRetainedSize();
        }

        public long getRetainedSizeInBytes()
        {
            return retainedSizeInBytes;
        }

        @Override
        protected Optional<Page> computeNext()
        {
            int batchSize;
            while (true) {
                if (selectedPositions.isEmpty()) {
                    updateRetainedSize();
                    verify(!forceYieldFinish);
                    return endOfData();
                }

                // we always process one chunk
                if (forceYieldFinish) {
                    // re-use the batch size from the last checkpoint
                    verify(previousBatchSize > 0);
                    batchSize = previousBatchSize;
                    forceYieldFinish = false;
                }
                else {
                    batchSize = Math.min(selectedPositions.size(), projectBatchSize);
                }
                Optional<Page> result = processBatch(batchSize);

                if (!result.isPresent()) {
                    // if we are running out of time
                    if (forceYieldFinish) {
                        return Optional.empty();
                    }

                    // if the page buffer filled up, so halve the batch size and retry
                    verify(batchSize > 1);
                    projectBatchSize = projectBatchSize / 2;
                    continue;
                }

                Page page = result.get();

                // if we produced a large page, halve the batch size for the next call
                long pageSize = page.getSizeInBytes();
                if (page.getPositionCount() > 1 && pageSize > MAX_PAGE_SIZE_IN_BYTES) {
                    projectBatchSize = projectBatchSize / 2;
                }

                // if we produced a small page, double the batch size for the next call
                if (pageSize < MIN_PAGE_SIZE_IN_BYTES && projectBatchSize < MAX_BATCH_SIZE) {
                    projectBatchSize = projectBatchSize * 2;
                }

                // remove batch from selectedPositions and previouslyComputedResults
                selectedPositions = selectedPositions.subRange(batchSize, selectedPositions.size());
                for (int i = 0; i < previouslyComputedResults.length; i++) {
                    if (previouslyComputedResults[i] != null && previouslyComputedResults[i].getPositionCount() > batchSize) {
                        previouslyComputedResults[i] = previouslyComputedResults[i].getRegion(batchSize, previouslyComputedResults[i].getPositionCount() - batchSize);
                    }
                    else {
                        previouslyComputedResults[i] = null;
                    }
                }

                updateRetainedSize();
                return Optional.of(page);
            }
        }

        private void updateRetainedSize()
        {
            // increment the size only when it is the first reference
            retainedSizeInBytes = 0;
            ReferenceCountMap referenceCountMap = new ReferenceCountMap();
            for (Block block : page.getBlocks()) {
                if (!isUnloadedLazyBlock(block)) {
                    block.retainedBytesForEachPart((object, size) -> {
                        if (referenceCountMap.incrementReference(object) == 1) {
                            retainedSizeInBytes += size;
                        }
                    });
                }
            }
            for (Block previouslyComputedResult : previouslyComputedResults) {
                if (previouslyComputedResult != null) {
                    previouslyComputedResult.retainedBytesForEachPart((object, size) -> {
                        if (referenceCountMap.incrementReference(object) == 1) {
                            retainedSizeInBytes += size;
                        }
                    });
                }
            }
        }

        private Optional<Page> processBatch(int batchSize)
        {
            Block[] blocks = new Block[projections.size()];

            int pageSize = 0;
            SelectedPositions positionsBatch = selectedPositions.subRange(0, batchSize);
            for (int i = 0; i < projections.size(); i++) {
                if (yieldSignal.isSet()) {
                    // save current batch size
                    forceYieldFinish = true;
                    previousBatchSize = batchSize;
                    return Optional.empty();
                }

                if (positionsBatch.size() > 1 && pageSize > MAX_PAGE_SIZE_IN_BYTES) {
                    return Optional.empty();
                }

                // if possible, use previouslyComputedResults produced in prior optimistic failure attempt
                PageProjection projection = projections.get(i);
                if (previouslyComputedResults[i] != null && previouslyComputedResults[i].getPositionCount() >= batchSize) {
                    blocks[i] = previouslyComputedResults[i].getRegion(0, batchSize);
                }
                else {
                    if (!pageProjectOutput.isPresent()) {
                        pageProjectOutput = Optional.of(projection.project(session, yieldSignal, projection.getInputChannels().getInputChannels(page), positionsBatch));
                    }
                    Optional<Block> block = pageProjectOutput.get().compute();
                    if (!block.isPresent()) {
                        forceYieldFinish = true;
                        previousBatchSize = batchSize;
                        return Optional.empty();
                    }
                    pageProjectOutput = Optional.empty();
                    previouslyComputedResults[i] = block.get();
                    blocks[i] = previouslyComputedResults[i];
                }

                pageSize += blocks[i].getSizeInBytes();
            }
            return Optional.of(new Page(positionsBatch.size(), blocks));
        }
    }

    @NotThreadSafe
    private static class DictionarySourceIdFunction
            implements Function<DictionaryBlock, DictionaryId>
    {
        private final Map<DictionaryId, DictionaryId> dictionarySourceIds = new HashMap<>();

        @Override
        public DictionaryId apply(DictionaryBlock block)
        {
            return dictionarySourceIds.computeIfAbsent(block.getDictionarySourceId(), ignored -> randomDictionaryId());
        }

        public void reset()
        {
            dictionarySourceIds.clear();
        }
    }
}
