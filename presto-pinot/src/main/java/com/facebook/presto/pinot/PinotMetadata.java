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
package com.facebook.presto.pinot;

import com.facebook.presto.spi.ColumnHandle;
import com.facebook.presto.spi.ColumnMetadata;
import com.facebook.presto.spi.ConnectorSession;
import com.facebook.presto.spi.ConnectorTableHandle;
import com.facebook.presto.spi.ConnectorTableLayout;
import com.facebook.presto.spi.ConnectorTableLayoutHandle;
import com.facebook.presto.spi.ConnectorTableLayoutResult;
import com.facebook.presto.spi.ConnectorTableMetadata;
import com.facebook.presto.spi.Constraint;
import com.facebook.presto.spi.SchemaTableName;
import com.facebook.presto.spi.SchemaTablePrefix;
import com.facebook.presto.spi.TableNotFoundException;
import com.facebook.presto.spi.connector.ConnectorMetadata;
import com.facebook.presto.spi.pipeline.AggregationPipelineNode;
import com.facebook.presto.spi.pipeline.FilterPipelineNode;
import com.facebook.presto.spi.pipeline.ProjectPipelineNode;
import com.facebook.presto.spi.pipeline.PushdownExpression;
import com.facebook.presto.spi.pipeline.PushdownInputColumn;
import com.facebook.presto.spi.pipeline.TablePipelineNode;
import com.facebook.presto.spi.pipeline.TableScanPipeline;
import com.facebook.presto.spi.type.Type;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.airlift.log.Logger;

import javax.inject.Inject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.facebook.presto.pinot.PinotColumnHandle.PinotColumnType.DERIVED;
import static com.facebook.presto.pinot.PinotColumnHandle.PinotColumnType.REGULAR;
import static com.facebook.presto.pinot.Types.checkType;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;

public class PinotMetadata
        implements ConnectorMetadata
{
    private static final Logger log = Logger.get(PinotMetadata.class);
    private final String connectorId;
    private final PinotConnection pinotPrestoConnection;

    @Inject
    public PinotMetadata(PinotConnectorId connectorId, PinotConnection pinotPrestoConnection)
    {
        this.connectorId = requireNonNull(connectorId, "connectorId is null").toString();
        this.pinotPrestoConnection = requireNonNull(pinotPrestoConnection, "pinotPrestoConnection is null");
    }

    private static boolean isSupportedAggregation(String aggregation)
    {
        switch (aggregation.toLowerCase(ENGLISH)) {
            case "count":
            case "min":
            case "max":
            case "sum":
            case "avg":
                return true;
            default:
                return aggregation.toLowerCase(ENGLISH).startsWith("percentile");
        }
    }

    @Override
    public List<String> listSchemaNames(ConnectorSession session)
    {
        return listSchemaNames();
    }

    public List<String> listSchemaNames()
    {
        try {
            ImmutableList.Builder<String> schemaNamesListBuilder = ImmutableList.builder();
            for (String table : pinotPrestoConnection.getTableNames()) {
                schemaNamesListBuilder.add(table.toLowerCase(ENGLISH));
            }
            return schemaNamesListBuilder.build();
        }
        catch (Exception e) {
            return ImmutableList.of();
        }
    }

    public String getPinotTableNameFromPrestoTableName(String prestoTableName)
    {
        try {
            for (String pinotTableName : pinotPrestoConnection.getTableNames()) {
                if (prestoTableName.equalsIgnoreCase(pinotTableName)) {
                    return pinotTableName;
                }
            }
        }
        catch (Exception e) {
        }
        return null;
    }

    @Override
    public PinotTableHandle getTableHandle(ConnectorSession session, SchemaTableName tableName)
    {
        if (!listSchemaNames(session).contains(tableName.getSchemaName().toLowerCase(ENGLISH))) {
            return null;
        }
        String pinotTableName = getPinotTableNameFromPrestoTableName(tableName.getTableName());
        try {
            PinotTable table = pinotPrestoConnection.getTable(pinotTableName);
            if (table == null) {
                return null;
            }
        }
        catch (Exception e) {
            log.error("Failed to get TableHandle for table : " + tableName);
            return null;
        }

        return new PinotTableHandle(connectorId, tableName.getSchemaName(), pinotTableName);
    }

    @Override
    public List<ConnectorTableLayoutResult> getTableLayouts(ConnectorSession session, ConnectorTableHandle table, Constraint<ColumnHandle> constraint, Optional<Set<ColumnHandle>> desiredColumns)
    {
        PinotTableHandle tableHandle = checkType(table, PinotTableHandle.class, "table");
        tableHandle.setConstraintSummary(constraint.getSummary());
        ConnectorTableLayout layout = new ConnectorTableLayout(new PinotTableLayoutHandle(tableHandle, Optional.empty()));
        return ImmutableList.of(new ConnectorTableLayoutResult(layout, constraint.getSummary()));
    }

    @Override
    public ConnectorTableLayout getTableLayout(ConnectorSession session, ConnectorTableLayoutHandle handle)
    {
        return new ConnectorTableLayout(handle);
    }

    @Override
    public ConnectorTableMetadata getTableMetadata(ConnectorSession session, ConnectorTableHandle table)
    {
        Stopwatch stopwatch = Stopwatch.createStarted();
        try {
            PinotTableHandle pinotTableHandle = checkType(table, PinotTableHandle.class, "table");
            checkArgument(pinotTableHandle.getConnectorId().equals(connectorId), "tableHandle is not for this connector");
            SchemaTableName tableName = new SchemaTableName(pinotTableHandle.getSchemaName(), pinotTableHandle.getTableName());

            return getTableMetadata(tableName);
        }
        finally {
            System.out.println("getTableMetadata: " + stopwatch.elapsed(TimeUnit.MILLISECONDS));
        }
    }

    @Override
    public List<SchemaTableName> listTables(ConnectorSession session, String schemaNameOrNull)
    {
        Stopwatch stopwatch = Stopwatch.createStarted();
        try {
            Collection<String> schemaNames;
            if (schemaNameOrNull != null) {
                schemaNames = ImmutableSet.of(schemaNameOrNull);
            }
            else {
                schemaNames = listSchemaNames();
            }

            ImmutableList.Builder<SchemaTableName> builder = ImmutableList.builder();
            try {
                for (String table : pinotPrestoConnection.getTableNames()) {
                    if (schemaNames.contains(table.toLowerCase(ENGLISH))) {
                        builder.add(new SchemaTableName(table.toLowerCase(ENGLISH), table));
                    }
                }
            }
            catch (Exception e) {
            }
            return builder.build();
        }
        finally {
            System.out.println("listTables: " + stopwatch.elapsed(TimeUnit.MILLISECONDS));
        }
    }

    @Override
    public Map<String, ColumnHandle> getColumnHandles(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        Stopwatch stopwatch = Stopwatch.createStarted();
        try {
            PinotTableHandle pinotTableHandle = checkType(tableHandle, PinotTableHandle.class, "tableHandle");
            checkArgument(pinotTableHandle.getConnectorId().equals(connectorId), "tableHandle is not for this connector");

            String pinotTableName = getPinotTableNameFromPrestoTableName(pinotTableHandle.getTableName());
            try {
                PinotTable table = pinotPrestoConnection.getTable(pinotTableName);
                if (table == null) {
                    throw new TableNotFoundException(pinotTableHandle.toSchemaTableName());
                }
                ImmutableMap.Builder<String, ColumnHandle> columnHandles = ImmutableMap.builder();
                for (ColumnMetadata column : table.getColumnsMetadata()) {
                    columnHandles.put(column.getName().toLowerCase(ENGLISH), new PinotColumnHandle(((PinotColumnMetadata) column).getPinotName(), column.getType(), REGULAR));
                }
                return columnHandles.build();
            }
            catch (Exception e) {
                log.error("Failed to get ColumnHandles for table : " + pinotTableHandle.getTableName(), e);
                return null;
            }
        }
        finally {
            System.out.println("getColumnHandles: " + stopwatch.elapsed(TimeUnit.MILLISECONDS));
        }
    }

    @Override
    public Map<SchemaTableName, List<ColumnMetadata>> listTableColumns(ConnectorSession session, SchemaTablePrefix prefix)
    {
        Stopwatch stopwatch = Stopwatch.createStarted();
        try {
            requireNonNull(prefix, "prefix is null");
            ImmutableMap.Builder<SchemaTableName, List<ColumnMetadata>> columns = ImmutableMap.builder();
            for (SchemaTableName tableName : listTables(session, prefix)) {
                ConnectorTableMetadata tableMetadata = getTableMetadata(tableName);
                // table can disappear during listing operation
                if (tableMetadata != null) {
                    columns.put(tableName, tableMetadata.getColumns());
                }
            }
            return columns.build();
        }
        finally {
            System.out.println("listTableColumns: " + stopwatch.elapsed(TimeUnit.MILLISECONDS));
        }
    }

    @Override
    public Optional<TableScanPipeline> pushProjectIntoScan(ConnectorSession session, ConnectorTableHandle connectorTableHandle,
            Optional<TableScanPipeline> currentPipeline, ProjectPipelineNode project)
    {
        // TODO: Make sure we can add aggregation to existing pipeline - each connector has its own limitations
        PinotTableHandle pinotTableHandle = (PinotTableHandle) connectorTableHandle;

        // Create temporary column handles. This is just to satisfy the existing contract of TableScanNode:assignments
        List<ColumnHandle> outputColumnHandles = new ArrayList<>();
        List<String> outputColumns = project.getOutputColumns();
        List<Type> outputColumnTypes = project.getRowType();
        List<PushdownExpression> pushdownExpressions = project.getNodes();

        for (int fieldId = 0; fieldId < outputColumns.size(); fieldId++) {
            // Create a column handle for the new output column
            outputColumnHandles.add(new PinotColumnHandle(
                    outputColumns.get(fieldId),
                    outputColumnTypes.get(fieldId),
                    pushdownExpressions.get(fieldId) instanceof PushdownInputColumn ? REGULAR : DERIVED));
        }

        // TODO: make sure Pinot supports all functions in project
        TableScanPipeline pipeline = createPipeline(currentPipeline, pinotTableHandle);
        pipeline.addPipeline(project, outputColumnHandles);

        return Optional.of(pipeline);
    }

    @Override
    public Optional<TableScanPipeline> pushFilterIntoScan(ConnectorSession session, ConnectorTableHandle connectorTableHandle,
            Optional<TableScanPipeline> currentPipeline, FilterPipelineNode filter)
    {
        // TODO: Make sure we can add aggregation to existing pipeline - each connector has its own limitations
        PinotTableHandle pinotTableHandle = (PinotTableHandle) connectorTableHandle;

        // Create temporary column handles. This is just to satisfy the existing contract of TableScanNode:assignments
        List<ColumnHandle> outputColumnHandles = new ArrayList<>();
        List<String> outputColumns = filter.getOutputColumns();
        List<Type> outputColumnTypes = filter.getRowType();

        for (int fieldId = 0; fieldId < outputColumns.size(); fieldId++) {
            // Create a column handle for the new output column
            outputColumnHandles.add(new PinotColumnHandle(
                    outputColumns.get(fieldId),
                    outputColumnTypes.get(fieldId),
                    DERIVED));
        }

        // TODO: make sure Pinot supports all functions in project
        TableScanPipeline pipeline = createPipeline(currentPipeline, pinotTableHandle);
        pipeline.addPipeline(filter, outputColumnHandles);

        return Optional.of(pipeline);
    }

    @Override
    public Optional<TableScanPipeline> pushAggregationIntoScan(ConnectorSession session, ConnectorTableHandle connectorTableHandle,
            Optional<TableScanPipeline> currentPipeline, AggregationPipelineNode aggregations)
    {
        // TODO: Make sure we can add aggregation to existing pipeline - each connector has its own limitations
        PinotTableHandle pinotTableHandle = (PinotTableHandle) connectorTableHandle;

        int numAggFunctions = 0;
        for (AggregationPipelineNode.Node expr : aggregations.getNodes()) {
            if (expr.getNodeType() == AggregationPipelineNode.NodeType.GROUP_BY) {
                continue;
            }

            numAggFunctions++;
            if (numAggFunctions > 1) {
                // currently only aggregation is supported per query due to the way Pinot REST proxy returns results
                return Optional.empty();
            }

            AggregationPipelineNode.Aggregation aggregation = (AggregationPipelineNode.Aggregation) expr;

            List<String> inputColumnNames = aggregation.getInputs();
            if (inputColumnNames.size() > 1) {
                return Optional.empty();
            }

            if (inputColumnNames.isEmpty()) {
                if ("count".equalsIgnoreCase(aggregation.getFunction())) {
                    continue;
                }

                return Optional.empty();
            }

            if (!isSupportedAggregation(aggregation.getFunction())) {
                return Optional.empty();
            }
        }

        // Create temporary column handles. This is just to satisfy the existing contract of TableScanNode:assignments
        List<ColumnHandle> outputColumnHandles = new ArrayList<>();
        for (AggregationPipelineNode.Node node : aggregations.getNodes()) {
            // Create a column handle for the new output column
            outputColumnHandles.add(new PinotColumnHandle(
                    node.getOutputColumn(),
                    node.getOutputType(),
                    node.getNodeType() == AggregationPipelineNode.NodeType.GROUP_BY ? REGULAR : DERIVED));
        }

        TableScanPipeline pipeline = createPipeline(currentPipeline, pinotTableHandle);
        pipeline.addPipeline(aggregations, outputColumnHandles);

        return Optional.of(pipeline);
    }

    private TableScanPipeline createPipeline(Optional<TableScanPipeline> scanPipeline, PinotTableHandle tableHandle)
    {
        if (scanPipeline.isPresent()) {
            return scanPipeline.get();
        }

        TableScanPipeline newScanPipeline = new TableScanPipeline();

        ConnectorTableMetadata tableMetadata = getTableMetadata(new SchemaTableName(tableHandle.getSchemaName(), tableHandle.getTableName()));

        TablePipelineNode tableNode = new TablePipelineNode(
                tableHandle.getSchemaName(),
                tableHandle.getTableName(),
                tableMetadata.getColumns().stream().map(c -> ((PinotColumnMetadata) c).getPinotName()).collect(Collectors.toList()),
                tableMetadata.getColumns().stream().map(c -> c.getName()).collect(Collectors.toList()),
                tableMetadata.getColumns().stream().map(c -> c.getType()).collect(Collectors.toList()));

        newScanPipeline.addPipeline(tableNode,
                tableMetadata.getColumns().stream()
                        .map(c -> new PinotColumnHandle(c.getName(), c.getType(), REGULAR)).collect(Collectors.toList()));

        return newScanPipeline;
    }

    @Override
    public Optional<ConnectorTableLayoutHandle> pushTableScanIntoConnectorLayoutHandle(ConnectorSession session, TableScanPipeline scanPipeline, ConnectorTableLayoutHandle
            connectorTableLayoutHandle)
    {
        PinotTableLayoutHandle currentHandle = (PinotTableLayoutHandle) connectorTableLayoutHandle;
        checkArgument(!currentHandle.getScanPipeline().isPresent(), "layout already has a scan pipeline");
        return Optional.of(
                new PinotTableLayoutHandle(
                        currentHandle.getTable(),
                        Optional.of(scanPipeline)));
    }

    private ConnectorTableMetadata getTableMetadata(SchemaTableName tableName)
    {
        if (!listSchemaNames().contains(tableName.getSchemaName())) {
            return null;
        }
        String pinotTableName = getPinotTableNameFromPrestoTableName(tableName.getTableName());
        try {
            PinotTable table = pinotPrestoConnection.getTable(pinotTableName);
            if (table == null) {
                return null;
            }
            return new ConnectorTableMetadata(tableName, table.getColumnsMetadata());
        }
        catch (Exception e) {
            log.error("Failed to get ConnectorTableMetadata for table: " + tableName.getTableName(), e);
            return null;
        }
    }

    private List<SchemaTableName> listTables(ConnectorSession session, SchemaTablePrefix prefix)
    {
        if (prefix.getSchemaName() == null) {
            return listTables(session, prefix.getSchemaName());
        }
        return ImmutableList.of(new SchemaTableName(prefix.getSchemaName(), prefix.getTableName()));
    }

    @Override
    public ColumnMetadata getColumnMetadata(ConnectorSession session, ConnectorTableHandle tableHandle, ColumnHandle columnHandle)
    {
        checkType(tableHandle, PinotTableHandle.class, "tableHandle");
        return checkType(columnHandle, PinotColumnHandle.class, "columnHandle").getColumnMetadata();
    }
}
