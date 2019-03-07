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
import com.facebook.presto.spi.pipeline.ProjectPipelineNode;
import com.facebook.presto.spi.pipeline.PushdownExpression;
import com.facebook.presto.spi.pipeline.TableScanPipeline;
import com.facebook.presto.sql.planner.Symbol;
import com.facebook.presto.sql.planner.TypeProvider;
import com.facebook.presto.sql.planner.iterative.Rule;
import com.facebook.presto.sql.planner.plan.Assignments;
import com.facebook.presto.sql.planner.plan.ProjectNode;
import com.facebook.presto.sql.planner.plan.TableScanNode;
import com.facebook.presto.sql.tree.Expression;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.facebook.presto.matching.Capture.newCapture;
import static com.facebook.presto.sql.planner.plan.Patterns.project;
import static com.facebook.presto.sql.planner.plan.Patterns.source;
import static com.facebook.presto.sql.planner.plan.Patterns.tableScan;
import static java.util.Objects.requireNonNull;

/**
 * Pushes project operation into table scan. Useful in connectors which can compute faster than Presto.
 *
 * <p>
 * From:
 * <pre>
 * - Project
 *   - TableScan
 * </pre>
 * To:
 * <pre>
 * - TableScan (with project expressions pushed into the scan)
 * </pre>
 * <p>
 */
public class PushProjectIntoTableScan
        implements Rule<ProjectNode>
{
    private static final Capture<TableScanNode> TABLE_SCAN = newCapture();
    private static final Pattern<ProjectNode> PATTERN = project()
            .with(source().matching(tableScan().capturedAs(TABLE_SCAN)));

    private final Metadata metadata;

    public PushProjectIntoTableScan(Metadata metadata)
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
    public Pattern<ProjectNode> getPattern()
    {
        return PATTERN;
    }

    @Override
    public boolean isEnabled(Session session)
    {
        return SystemSessionProperties.isPushdownProjectIntoScan(session);
    }

    @Override
    public Result apply(ProjectNode projectNode, Captures captures, Context context)
    {
        TableScanNode scanNode = captures.get(TABLE_SCAN);

        Optional<ProjectPipelineNode> projectPipelineNode = inConnectorFormat(
                projectNode.getOutputSymbols(),
                projectNode.getAssignments(),
                context.getSymbolAllocator().getTypes());

        if (!projectPipelineNode.isPresent()) {
            return Result.empty();
        }

        Optional<TableScanPipeline> newScanPipeline = metadata.pushProjectIntoScan(
                context.getSession(), scanNode.getTable(), scanNode.getScanPipeline(), projectPipelineNode.get());

        if (newScanPipeline.isPresent()) {
            return Result.ofPlanNode(new TableScanNode(
                    context.getIdAllocator().getNextId(),
                    scanNode.getTable(),
                    projectNode.getOutputSymbols(),
                    createAssignments(newScanPipeline.get().getOutputColumnHandles(), projectNode.getOutputSymbols()),
                    scanNode.getLayout(),
                    scanNode.getCurrentConstraint(),
                    scanNode.getEnforcedConstraint(),
                    newScanPipeline));
        }

        return Result.empty();
    }

    private Optional<ProjectPipelineNode> inConnectorFormat(List<Symbol> outputSymbols, Assignments assignments, TypeProvider typeProvider)
    {
        Map<Symbol, Expression> assignMap = assignments.getMap();

        List<PushdownExpression> pushdownExpressions = new ArrayList<>();
        PushdownExpressionGenerator visitor = new PushdownExpressionGenerator();
        for (Symbol output : outputSymbols) {
            Expression expression = assignMap.get(output);
            PushdownExpression pushdownExpression = visitor.process(expression);
            if (pushdownExpression == null) {
                return Optional.empty();
            }

            pushdownExpressions.add(pushdownExpression);
        }

        return Optional.of(new ProjectPipelineNode(
                pushdownExpressions,
                outputSymbols.stream().map(s -> s.getName()).collect(Collectors.toList()),
                outputSymbols.stream().map(s -> typeProvider.get(s)).collect(Collectors.toList())));
    }
}
