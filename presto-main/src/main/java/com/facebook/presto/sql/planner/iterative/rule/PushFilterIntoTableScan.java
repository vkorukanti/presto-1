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
package com.facebook.presto.sql.planner.iterative.rule;

import com.facebook.presto.Session;
import com.facebook.presto.SystemSessionProperties;
import com.facebook.presto.matching.Capture;
import com.facebook.presto.matching.Captures;
import com.facebook.presto.matching.Pattern;
import com.facebook.presto.metadata.Metadata;
import com.facebook.presto.spi.ColumnHandle;
import com.facebook.presto.spi.pipeline.FilterPipelineNode;
import com.facebook.presto.spi.pipeline.PushdownExpression;
import com.facebook.presto.spi.pipeline.TableScanPipeline;
import com.facebook.presto.sql.planner.Symbol;
import com.facebook.presto.sql.planner.TypeProvider;
import com.facebook.presto.sql.planner.iterative.Rule;
import com.facebook.presto.sql.planner.plan.FilterNode;
import com.facebook.presto.sql.planner.plan.TableScanNode;
import com.facebook.presto.sql.tree.Expression;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.facebook.presto.matching.Capture.newCapture;
import static com.facebook.presto.sql.planner.plan.Patterns.filter;
import static com.facebook.presto.sql.planner.plan.Patterns.source;
import static com.facebook.presto.sql.planner.plan.Patterns.tableScan;
import static java.util.Objects.requireNonNull;

/**
 * Pushes filter operation into table scan. Useful in connectors which can compute faster than Presto.
 *
 * <p>
 * From:
 * <pre>
 * - Filter
 *   - TableScan
 * </pre>
 * To:
 * <pre>
 * - TableScan (with filter expressions pushed into the scan)
 * </pre>
 * <p>
 */
public class PushFilterIntoTableScan
        implements Rule<FilterNode>
{
    private static final Capture<TableScanNode> TABLE_SCAN = newCapture();
    private static final Pattern<FilterNode> PATTERN = filter()
            .with(source().matching(tableScan().capturedAs(TABLE_SCAN)));

    private final Metadata metadata;

    public PushFilterIntoTableScan(Metadata metadata)
    {
        this.metadata = requireNonNull(metadata, "metadata is null");
    }

    private static Map<Symbol, ColumnHandle> createAssignments(List<ColumnHandle> outputColumnHandles, List<Symbol> outputSymbols)
    {
        Map<Symbol, ColumnHandle> assignments = new HashMap<>();
        for (int i = 0; i < outputSymbols.size(); i++) {
            assignments.put(outputSymbols.get(i), outputColumnHandles.get(i));
        }

        return assignments;
    }

    @Override
    public Pattern<FilterNode> getPattern()
    {
        return PATTERN;
    }

    @Override
    public boolean isEnabled(Session session)
    {
        return SystemSessionProperties.isPushdownFilterIntoScan(session);
    }

    @Override
    public Result apply(FilterNode filter, Captures captures, Context context)
    {
        TableScanNode scanNode = captures.get(TABLE_SCAN);

        Optional<FilterPipelineNode> filterPipelineNode = inConnectorFormat(
                filter.getOutputSymbols(),
                filter.getPredicate(),
                context.getSymbolAllocator().getTypes());

        if (!filterPipelineNode.isPresent()) {
            return Result.empty();
        }

        Optional<TableScanPipeline> newScanPipeline = metadata.pushFilterIntoScan(
                context.getSession(), scanNode.getTable(), scanNode.getScanPipeline(), filterPipelineNode.get());

        if (newScanPipeline.isPresent()) {
            return Result.ofPlanNode(new TableScanNode(
                    context.getIdAllocator().getNextId(),
                    scanNode.getTable(),
                    filter.getOutputSymbols(),
                    createAssignments(newScanPipeline.get().getOutputColumnHandles(), filter.getOutputSymbols()),
                    scanNode.getLayout(),
                    scanNode.getCurrentConstraint(),
                    scanNode.getEnforcedConstraint(),
                    newScanPipeline));
        }

        return Result.empty();
    }

    private Optional<FilterPipelineNode> inConnectorFormat(List<Symbol> outputSymbols, Expression predicate, TypeProvider typeProvider)
    {
        PushdownExpression pushdownPredicate = new PushdownExpressionGenerator().process(predicate);

        if (pushdownPredicate == null) {
            return Optional.empty();
        }

        return Optional.of(new FilterPipelineNode(
                pushdownPredicate,
                outputSymbols.stream().map(s -> s.getName()).collect(Collectors.toList()),
                outputSymbols.stream().map(s -> typeProvider.get(s)).collect(Collectors.toList())));
    }
}
